#include <cassert>
#include <initializer_list>

#define TSF_IMPLEMENTATION
#include "tsf.h"
#include "voice-stealing.h"

static bool hasActiveKey(const tsf* synth, int key) {
    for (int i = 0; i < synth->voiceNum; ++i) {
        if (synth->voices[i].playingPreset != -1 && synth->voices[i].playingKey == key) {
            return true;
        }
    }
    return false;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    tsf* synth = tsf_load_filename(argv[1]);
    assert(synth != nullptr);
    tsf_set_output(synth, TSF_STEREO_INTERLEAVED, 48000, -6.0f);
    assert(tsf_set_max_voices(synth, 2));

    tsf_channel_set_presetindex(synth, 0, 0);
    palco_prepare_voice_slots(synth, 0, 60, 1.0f, 2);
    assert(tsf_channel_note_on(synth, 0, 60, 1.0f));
    palco_prepare_voice_slots(synth, 0, 64, 1.0f, 2);
    assert(tsf_channel_note_on(synth, 0, 64, 1.0f));
    assert(hasActiveKey(synth, 60));
    assert(hasActiveKey(synth, 64));

    // With both slots held, the newest note must replace the oldest one.
    palco_prepare_voice_slots(synth, 0, 67, 1.0f, 2);
    assert(tsf_channel_note_on(synth, 0, 67, 1.0f));
    assert(!hasActiveKey(synth, 60));
    assert(hasActiveKey(synth, 64));
    assert(hasActiveKey(synth, 67));
    assert(tsf_active_voice_count(synth) == 2);

    tsf_close(synth);

    // The adaptive budget can be smaller than the preallocated voice pool.
    synth = tsf_load_filename(argv[1]);
    assert(synth != nullptr);
    tsf_set_output(synth, TSF_STEREO_INTERLEAVED, 48000, -6.0f);
    assert(tsf_set_max_voices(synth, 32));
    tsf_channel_set_presetindex(synth, 0, 0);
    for (const int key : {60, 64, 67, 72}) {
        palco_prepare_voice_slots(synth, 0, key, 1.0f, 3);
        assert(tsf_channel_note_on(synth, 0, key, 1.0f));
        palco_trim_voice_count(synth, 3);
    }
    assert(tsf_active_voice_count(synth) == 3);
    assert(!hasActiveKey(synth, 60));
    assert(hasActiveKey(synth, 72));
    tsf_close(synth);
    return 0;
}
