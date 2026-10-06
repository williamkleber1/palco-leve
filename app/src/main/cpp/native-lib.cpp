#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define TSF_IMPLEMENTATION
#include "tsf.h"

#define LOG_TAG "PalcoLeve"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

enum class EventType : uint8_t {
    NoteOn,
    NoteOff,
    Control,
    Pitch,
    Preset,
    Volume,
    Panic
};

struct MidiEvent {
    EventType type;
    int channel;
    int a;
    int b;
    float value;
};

class AudioEngine {
public:
    static AudioEngine& instance() {
        static AudioEngine engine;
        return engine;
    }

    int loadSoundFont(const char* path) {
        std::lock_guard<std::mutex> lock(controlMutex_);
        stopLocked();
        clearQueue();

        tsf* candidate = tsf_load_filename(path);
        if (!candidate) {
            lastError_ = "Não foi possível abrir o arquivo SF2";
            return 0;
        }

        tsf_set_output(candidate, TSF_STEREO_INTERLEAVED, 48000, -6.0f);
        if (!tsf_set_max_voices(candidate, 96)) {
            tsf_close(candidate);
            lastError_ = "Memória insuficiente para preparar as vozes";
            return 0;
        }

        for (int channel = 0; channel < 16; ++channel) {
            tsf_channel_set_presetindex(candidate, channel, 0);
            tsf_channel_set_pitchrange(candidate, channel, 2.0f);
        }

        if (synth_) tsf_close(synth_);
        synth_ = candidate;
        presetNames_.clear();
        const int count = tsf_get_presetcount(synth_);
        presetNames_.reserve(std::max(0, count));
        for (int i = 0; i < count; ++i) {
            const char* name = tsf_get_presetname(synth_, i);
            presetNames_.emplace_back(name ? name : "Preset");
        }

        lastError_.clear();
        activeVoices_.store(0, std::memory_order_relaxed);
        LOGI("SF2 carregado com %d presets", count);
        return count;
    }

    bool start() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (!synth_) {
            lastError_ = "Carregue um SF2 antes de iniciar";
            return false;
        }
        if (stream_ && running_.load(std::memory_order_acquire)) return true;
        if (stream_) {
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK || !builder) {
            lastError_ = "AAudio não criou o stream";
            return false;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

        result = AAudioStreamBuilder_openStream(builder, &stream_);
        if (result != AAUDIO_OK) {
            stream_ = nullptr;
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            result = AAudioStreamBuilder_openStream(builder, &stream_);
        }
        AAudioStreamBuilder_delete(builder);

        if (result != AAUDIO_OK || !stream_) {
            lastError_ = std::string("Falha ao abrir AAudio: ") + AAudio_convertResultToText(result);
            stream_ = nullptr;
            return false;
        }

        sampleRate_ = AAudioStream_getSampleRate(stream_);
        framesPerBurst_ = AAudioStream_getFramesPerBurst(stream_);
        tsf_set_output(synth_, TSF_STEREO_INTERLEAVED, sampleRate_, -6.0f);

        if (framesPerBurst_ > 0) {
            AAudioStream_setBufferSizeInFrames(stream_, framesPerBurst_ * 2);
        }

        result = AAudioStream_requestStart(stream_);
        if (result != AAUDIO_OK) {
            lastError_ = std::string("Falha ao iniciar AAudio: ") + AAudio_convertResultToText(result);
            AAudioStream_close(stream_);
            stream_ = nullptr;
            return false;
        }

        running_.store(true, std::memory_order_release);
        lastError_.clear();
        LOGI("AAudio iniciado: %d Hz, burst %d", sampleRate_, framesPerBurst_);
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        stopLocked();
    }

    void noteOn(int channel, int note, int velocity) {
        MidiEvent event{EventType::NoteOn, channel & 15,
                        std::clamp(note, 0, 127), std::clamp(velocity, 0, 127), 0};
        enqueue(event);
    }

    void noteOff(int channel, int note) {
        MidiEvent event{EventType::NoteOff, channel & 15,
                        std::clamp(note, 0, 127), 0, 0};
        enqueue(event);
    }

    void control(int channel, int controller, int value) {
        MidiEvent event{EventType::Control, channel & 15,
                        std::clamp(controller, 0, 127), std::clamp(value, 0, 127), 0};
        enqueue(event);
    }

    void pitch(int channel, int value) {
        MidiEvent event{EventType::Pitch, channel & 15,
                        std::clamp(value, 0, 16383), 0, 0};
        enqueue(event);
    }

    void setPreset(int preset) {
        MidiEvent event{EventType::Preset, 0, std::max(0, preset), 0, 0};
        enqueue(event);
    }

    void setVolume(float volume) {
        MidiEvent event{EventType::Volume, 0, 0, 0,
                        std::clamp(volume, 0.0f, 1.2f)};
        enqueue(event);
    }

    void panic() {
        MidiEvent event{EventType::Panic, 0, 0, 0, 0};
        enqueue(event);
    }

    std::vector<std::string> presetNames() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        return presetNames_;
    }

    std::string status() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (!lastError_.empty()) return lastError_;
        if (!synth_) return "Nenhum SF2 carregado";
        if (!stream_) return "SF2 pronto • áudio parado";
        if (!running_.load(std::memory_order_acquire)) {
            return "Áudio interrompido • toque em iniciar";
        }

        const int32_t buffer = AAudioStream_getBufferSizeInFrames(stream_);
        const int32_t xruns = AAudioStream_getXRunCount(stream_);
        const int voices = activeVoices_.load(std::memory_order_relaxed);
        return std::to_string(sampleRate_) + " Hz • buffer "
                + std::to_string(buffer) + " • falhas "
                + std::to_string(std::max(0, xruns)) + " • vozes "
                + std::to_string(voices);
    }

private:
    static constexpr uint32_t QueueSize = 1024;

    AudioEngine() = default;
    ~AudioEngine() {
        stop();
        if (synth_) tsf_close(synth_);
    }

    static aaudio_data_callback_result_t dataCallback(
            AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
        return static_cast<AudioEngine*>(userData)->render(
                static_cast<float*>(audioData), numFrames);
    }

    static void errorCallback(AAudioStream*, void* userData, aaudio_result_t error) {
        auto* engine = static_cast<AudioEngine*>(userData);
        engine->running_.store(false, std::memory_order_release);
        LOGE("Erro AAudio: %s", AAudio_convertResultToText(error));
    }

    aaudio_data_callback_result_t render(float* output, int32_t frames) {
        if (!synth_ || !running_.load(std::memory_order_acquire)) {
            std::memset(output, 0, sizeof(float) * frames * 2);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }

        MidiEvent event{};
        while (dequeue(event)) apply(event);
        tsf_render_float(synth_, output, frames, 0);
        activeVoices_.store(tsf_active_voice_count(synth_), std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    void apply(const MidiEvent& event) {
        switch (event.type) {
            case EventType::NoteOn:
                tsf_channel_note_on(synth_, event.channel, event.a, event.b / 127.0f);
                break;
            case EventType::NoteOff:
                tsf_channel_note_off(synth_, event.channel, event.a);
                break;
            case EventType::Control:
                tsf_channel_midi_control(synth_, event.channel, event.a, event.b);
                break;
            case EventType::Pitch:
                tsf_channel_set_pitchwheel(synth_, event.channel, event.a);
                break;
            case EventType::Preset:
                for (int channel = 0; channel < 16; ++channel) {
                    tsf_channel_set_presetindex(synth_, channel, event.a);
                }
                break;
            case EventType::Volume:
                tsf_set_volume(synth_, event.value);
                break;
            case EventType::Panic:
                for (int channel = 0; channel < 16; ++channel) {
                    tsf_channel_sounds_off_all(synth_, channel);
                }
                break;
        }
    }

    bool enqueue(const MidiEvent& event) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        const uint32_t write = writeIndex_.load(std::memory_order_relaxed);
        const uint32_t next = (write + 1) % QueueSize;
        if (next == readIndex_.load(std::memory_order_acquire)) return false;
        queue_[write] = event;
        writeIndex_.store(next, std::memory_order_release);
        return true;
    }

    bool dequeue(MidiEvent& event) {
        const uint32_t read = readIndex_.load(std::memory_order_relaxed);
        if (read == writeIndex_.load(std::memory_order_acquire)) return false;
        event = queue_[read];
        readIndex_.store((read + 1) % QueueSize, std::memory_order_release);
        return true;
    }

    void clearQueue() {
        readIndex_.store(0, std::memory_order_relaxed);
        writeIndex_.store(0, std::memory_order_relaxed);
    }

    void stopLocked() {
        running_.store(false, std::memory_order_release);
        if (stream_) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);
            stream_ = nullptr;
        }
        if (synth_) tsf_reset(synth_);
        activeVoices_.store(0, std::memory_order_relaxed);
    }

    std::mutex controlMutex_;
    std::mutex producerMutex_;
    tsf* synth_ = nullptr;
    AAudioStream* stream_ = nullptr;
    std::vector<std::string> presetNames_;
    std::string lastError_;
    std::array<MidiEvent, QueueSize> queue_{};
    std::atomic<uint32_t> readIndex_{0};
    std::atomic<uint32_t> writeIndex_{0};
    std::atomic<bool> running_{false};
    std::atomic<int> activeVoices_{0};
    int32_t sampleRate_ = 0;
    int32_t framesPerBurst_ = 0;
};

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_wk_palcoleve_MainActivity_nativeLoadSoundFont(
        JNIEnv* env, jclass, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const int count = AudioEngine::instance().loadSoundFont(chars);
    env->ReleaseStringUTFChars(path, chars);
    return count;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_wk_palcoleve_MainActivity_nativeGetPresetNames(
        JNIEnv* env, jclass) {
    const auto names = AudioEngine::instance().presetNames();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(names.size()), stringClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(names[i].c_str()));
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wk_palcoleve_MainActivity_nativeStart(JNIEnv*, jclass) {
    return AudioEngine::instance().start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeStop(JNIEnv*, jclass) {
    AudioEngine::instance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeNoteOn(
        JNIEnv*, jclass, jint channel, jint note, jint velocity) {
    AudioEngine::instance().noteOn(channel, note, velocity);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeNoteOff(
        JNIEnv*, jclass, jint channel, jint note) {
    AudioEngine::instance().noteOff(channel, note);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeControlChange(
        JNIEnv*, jclass, jint channel, jint controller, jint value) {
    AudioEngine::instance().control(channel, controller, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativePitchBend(
        JNIEnv*, jclass, jint channel, jint value) {
    AudioEngine::instance().pitch(channel, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeSetPreset(
        JNIEnv*, jclass, jint preset) {
    AudioEngine::instance().setPreset(preset);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeSetVolume(
        JNIEnv*, jclass, jfloat volume) {
    AudioEngine::instance().setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativePanic(JNIEnv*, jclass) {
    AudioEngine::instance().panic();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wk_palcoleve_MainActivity_nativeGetStatus(JNIEnv* env, jclass) {
    return toJString(env, AudioEngine::instance().status());
}
