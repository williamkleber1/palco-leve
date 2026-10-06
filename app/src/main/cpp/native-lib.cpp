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
#include "voice-stealing.h"

#define LOG_TAG "PalcoLeve"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int TrackCount = 5;

enum class EventType : uint8_t {
    NoteOn, NoteOff, Control, Pitch, TrackPreset,
    MasterVolume, TrackVolume, TrackEnabled, Panic
};

struct MidiEvent {
    EventType type;
    int track;
    int channel;
    int a;
    int b;
    float value;
};

struct Track {
    tsf* synth = nullptr;
    std::vector<std::string> presetNames;
    bool enabled = true;
    float volume = 1.0f;
};

class AudioEngine {
public:
    static AudioEngine& instance() {
        static AudioEngine engine;
        return engine;
    }

    int loadSoundFont(int trackIndex, const char* path) {
        if (!validTrack(trackIndex)) return 0;
        std::lock_guard<std::mutex> lock(controlMutex_);
        stopLocked();
        clearQueue();

        tsf* candidate = tsf_load_filename(path);
        if (!candidate) {
            lastError_ = "Não foi possível abrir o arquivo SF2";
            return 0;
        }

        tsf_set_output(candidate, TSF_STEREO_INTERLEAVED, 48000, -6.0f);
        if (!tsf_set_max_voices(candidate, 32)) {
            tsf_close(candidate);
            lastError_ = "Memória insuficiente para preparar as vozes";
            return 0;
        }
        for (int channel = 0; channel < 16; ++channel) {
            tsf_channel_set_presetindex(candidate, channel, 0);
            tsf_channel_set_pitchrange(candidate, channel, 2.0f);
        }

        Track& track = tracks_[trackIndex];
        if (track.synth) tsf_close(track.synth);
        track.synth = candidate;
        track.enabled = true;
        track.presetNames.clear();
        const int count = tsf_get_presetcount(candidate);
        track.presetNames.reserve(std::max(0, count));
        for (int i = 0; i < count; ++i) {
            const char* name = tsf_get_presetname(candidate, i);
            track.presetNames.emplace_back(name ? name : "Preset");
        }
        updateGains();
        lastError_.clear();
        activeVoices_.store(0, std::memory_order_relaxed);
        LOGI("Camada %d carregada com %d presets", trackIndex + 1, count);
        return count;
    }

    bool start() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (loadedTrackCount() == 0) {
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
        for (Track& track : tracks_) {
            if (track.synth) {
                tsf_set_output(track.synth, TSF_STEREO_INTERLEAVED, sampleRate_, -6.0f);
            }
        }
        updateGains();
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
        LOGI("AAudio iniciado: %d Hz, burst %d, %d camadas",
             sampleRate_, framesPerBurst_, loadedTrackCount());
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        stopLocked();
    }

    void noteOn(int channel, int note, int velocity) {
        enqueue({EventType::NoteOn, -1, channel & 15,
                 std::clamp(note, 0, 127), std::clamp(velocity, 0, 127), 0});
    }
    void noteOff(int channel, int note) {
        enqueue({EventType::NoteOff, -1, channel & 15,
                 std::clamp(note, 0, 127), 0, 0});
    }
    void control(int channel, int controller, int value) {
        enqueue({EventType::Control, -1, channel & 15,
                 std::clamp(controller, 0, 127), std::clamp(value, 0, 127), 0});
    }
    void pitch(int channel, int value) {
        enqueue({EventType::Pitch, -1, channel & 15,
                 std::clamp(value, 0, 16383), 0, 0});
    }
    void setPreset(int track, int preset) {
        if (validTrack(track)) {
            enqueue({EventType::TrackPreset, track, 0, std::max(0, preset), 0, 0});
        }
    }
    void setMasterVolume(float volume) {
        enqueue({EventType::MasterVolume, -1, 0, 0, 0,
                 std::clamp(volume, 0.0f, 1.2f)});
    }
    void setTrackVolume(int track, float volume) {
        if (validTrack(track)) {
            enqueue({EventType::TrackVolume, track, 0, 0, 0,
                     std::clamp(volume, 0.0f, 1.0f)});
        }
    }
    void setTrackEnabled(int track, bool enabled) {
        if (validTrack(track)) {
            enqueue({EventType::TrackEnabled, track, 0, enabled ? 1 : 0, 0, 0});
        }
    }
    void panic() { enqueue({EventType::Panic, -1, 0, 0, 0, 0}); }

    std::vector<std::string> presetNames(int track) {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (!validTrack(track)) return {};
        return tracks_[track].presetNames;
    }

    std::string status() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (!lastError_.empty()) return lastError_;
        const int loaded = loadedTrackCount();
        if (loaded == 0) return "Nenhum SF2 carregado";
        if (!stream_) return std::to_string(loaded) + " camadas prontas • áudio parado";
        if (!running_.load(std::memory_order_acquire)) {
            return "Áudio interrompido • toque em iniciar";
        }
        const int32_t buffer = AAudioStream_getBufferSizeInFrames(stream_);
        const int32_t xruns = AAudioStream_getXRunCount(stream_);
        const int voices = activeVoices_.load(std::memory_order_relaxed);
        return std::to_string(sampleRate_) + " Hz • buffer "
                + std::to_string(buffer) + " • falhas "
                + std::to_string(std::max(0, xruns)) + " • camadas "
                + std::to_string(loaded) + " • vozes " + std::to_string(voices);
    }

private:
    static constexpr uint32_t QueueSize = 1024;
    AudioEngine() = default;
    ~AudioEngine() {
        stop();
        for (Track& track : tracks_) if (track.synth) tsf_close(track.synth);
    }

    static bool validTrack(int track) { return track >= 0 && track < TrackCount; }
    int loadedTrackCount() const {
        int count = 0;
        for (const Track& track : tracks_) if (track.synth) ++count;
        return count;
    }
    int enabledTrackCount() const {
        int count = 0;
        for (const Track& track : tracks_) {
            if (track.synth && track.enabled) ++count;
        }
        return count;
    }
    void updateGains() {
        const float normalization = 1.0f / std::sqrt(
                static_cast<float>(std::max(1, enabledTrackCount())));
        for (Track& track : tracks_) {
            if (track.synth) {
                tsf_set_volume(track.synth, masterVolume_ * track.volume * normalization);
            }
        }
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
        if (!running_.load(std::memory_order_acquire)) {
            std::memset(output, 0, sizeof(float) * frames * 2);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        MidiEvent event{};
        while (dequeue(event)) apply(event);

        bool mixed = false;
        int voices = 0;
        for (Track& track : tracks_) {
            if (!track.synth || !track.enabled) continue;
            tsf_render_float(track.synth, output, frames, mixed ? 1 : 0);
            mixed = true;
            voices += tsf_active_voice_count(track.synth);
        }
        if (!mixed) std::memset(output, 0, sizeof(float) * frames * 2);
        activeVoices_.store(voices, std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    void apply(const MidiEvent& event) {
        switch (event.type) {
            case EventType::NoteOn:
                for (Track& track : tracks_) {
                    if (track.synth && track.enabled) {
                        const float velocity = event.b / 127.0f;
                        palco_prepare_voice_slots(track.synth, event.channel, event.a, velocity);
                        tsf_channel_note_on(track.synth, event.channel, event.a, velocity);
                    }
                }
                break;
            case EventType::NoteOff:
                for (Track& track : tracks_) {
                    if (track.synth) tsf_channel_note_off(track.synth, event.channel, event.a);
                }
                break;
            case EventType::Control:
                for (Track& track : tracks_) {
                    if (track.synth) {
                        tsf_channel_midi_control(track.synth, event.channel, event.a, event.b);
                    }
                }
                break;
            case EventType::Pitch:
                for (Track& track : tracks_) {
                    if (track.synth) {
                        tsf_channel_set_pitchwheel(track.synth, event.channel, event.a);
                    }
                }
                break;
            case EventType::TrackPreset:
                if (validTrack(event.track) && tracks_[event.track].synth) {
                    for (int channel = 0; channel < 16; ++channel) {
                        tsf_channel_set_presetindex(tracks_[event.track].synth,
                                                    channel, event.a);
                    }
                }
                break;
            case EventType::MasterVolume:
                masterVolume_ = event.value;
                updateGains();
                break;
            case EventType::TrackVolume:
                if (validTrack(event.track)) {
                    tracks_[event.track].volume = event.value;
                    updateGains();
                }
                break;
            case EventType::TrackEnabled:
                if (validTrack(event.track)) {
                    Track& track = tracks_[event.track];
                    track.enabled = event.a != 0;
                    if (!track.enabled && track.synth) {
                        for (int channel = 0; channel < 16; ++channel) {
                            tsf_channel_sounds_off_all(track.synth, channel);
                        }
                    }
                    updateGains();
                }
                break;
            case EventType::Panic:
                for (Track& track : tracks_) {
                    if (!track.synth) continue;
                    for (int channel = 0; channel < 16; ++channel) {
                        tsf_channel_sounds_off_all(track.synth, channel);
                    }
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
        for (Track& track : tracks_) if (track.synth) tsf_reset(track.synth);
        activeVoices_.store(0, std::memory_order_relaxed);
    }

    std::mutex controlMutex_;
    std::mutex producerMutex_;
    std::array<Track, TrackCount> tracks_{};
    AAudioStream* stream_ = nullptr;
    std::string lastError_;
    std::array<MidiEvent, QueueSize> queue_{};
    std::atomic<uint32_t> readIndex_{0};
    std::atomic<uint32_t> writeIndex_{0};
    std::atomic<bool> running_{false};
    std::atomic<int> activeVoices_{0};
    float masterVolume_ = 0.8f;
    int32_t sampleRate_ = 0;
    int32_t framesPerBurst_ = 0;
};

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_wk_palcoleve_MainActivity_nativeLoadSoundFont(
        JNIEnv* env, jclass, jint track, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    const int count = AudioEngine::instance().loadSoundFont(track, chars);
    env->ReleaseStringUTFChars(path, chars);
    return count;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_wk_palcoleve_MainActivity_nativeGetPresetNames(
        JNIEnv* env, jclass, jint track) {
    const auto names = AudioEngine::instance().presetNames(track);
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
        JNIEnv*, jclass, jint track, jint preset) {
    AudioEngine::instance().setPreset(track, preset);
}
extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeSetVolume(
        JNIEnv*, jclass, jfloat volume) {
    AudioEngine::instance().setMasterVolume(volume);
}
extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeSetTrackVolume(
        JNIEnv*, jclass, jint track, jfloat volume) {
    AudioEngine::instance().setTrackVolume(track, volume);
}
extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativeSetTrackEnabled(
        JNIEnv*, jclass, jint track, jboolean enabled) {
    AudioEngine::instance().setTrackEnabled(track, enabled == JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL
Java_com_wk_palcoleve_MainActivity_nativePanic(JNIEnv*, jclass) {
    AudioEngine::instance().panic();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_wk_palcoleve_MainActivity_nativeGetStatus(JNIEnv* env, jclass) {
    return toJString(env, AudioEngine::instance().status());
}
