#pragma once

// TinySoundFont only reuses voices that are already in release. With a fixed
// voice pool, a fully held chord would otherwise cause newer notes to vanish.
// This helper runs on the audio thread immediately before tsf_channel_note_on.
static inline void palco_prepare_voice_slots(tsf* synth, int channel, int key, float velocity) {
    if (!synth || !synth->maxVoiceNum || !synth->channels || velocity <= 0.0f ||
        channel < 0 || channel >= synth->channels->channelNum) {
        return;
    }

    const int presetIndex = synth->channels->channels[channel].presetIndex;
    if (presetIndex < 0 || presetIndex >= synth->presetNum) return;

    const short midiVelocity = static_cast<short>(velocity * 127.0f);
    const tsf_preset& preset = synth->presets[presetIndex];
    int requiredSlots = 0;
    for (const tsf_region* region = preset.regions;
         region != preset.regions + preset.regionNum; ++region) {
        if (key >= region->lokey && key <= region->hikey &&
            midiVelocity >= region->lovel && midiVelocity <= region->hivel) {
            ++requiredSlots;
        }
    }
    if (requiredSlots <= 0) return;
    if (requiredSlots > synth->maxVoiceNum) requiredSlots = synth->maxVoiceNum;

    // Free slots and voices already in release are handled by TinySoundFont.
    int reusableSlots = 0;
    for (int i = 0; i < synth->voiceNum; ++i) {
        const tsf_voice& voice = synth->voices[i];
        if (voice.playingPreset == -1 || voice.ampenv.segment == TSF_SEGMENT_RELEASE) {
            ++reusableSlots;
        }
    }

    int voicesToSteal = requiredSlots - reusableSlots;
    while (voicesToSteal-- > 0) {
        tsf_voice* oldest = nullptr;
        for (int i = 0; i < synth->voiceNum; ++i) {
            tsf_voice* candidate = &synth->voices[i];
            if (candidate->playingPreset == -1 ||
                candidate->ampenv.segment == TSF_SEGMENT_RELEASE) {
                continue;
            }
            if (!oldest || candidate->playIndex < oldest->playIndex ||
                (candidate->playIndex == oldest->playIndex &&
                 candidate->ampenv.level < oldest->ampenv.level)) {
                oldest = candidate;
            }
        }
        if (!oldest) break;
        tsf_voice_kill(oldest);
    }
}
