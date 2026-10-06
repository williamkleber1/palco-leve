#pragma once

static inline int palco_active_voice_count(const tsf* synth) {
    int active = 0;
    for (int i = 0; i < synth->voiceNum; ++i) {
        if (synth->voices[i].playingPreset != -1) ++active;
    }
    return active;
}

static inline tsf_voice* palco_voice_to_steal(tsf* synth) {
    tsf_voice* quietestRelease = nullptr;
    tsf_voice* oldestHeld = nullptr;
    for (int i = 0; i < synth->voiceNum; ++i) {
        tsf_voice* candidate = &synth->voices[i];
        if (candidate->playingPreset == -1) continue;
        if (candidate->ampenv.segment == TSF_SEGMENT_RELEASE) {
            if (!quietestRelease || candidate->ampenv.level < quietestRelease->ampenv.level) {
                quietestRelease = candidate;
            }
            continue;
        }
        if (!oldestHeld || candidate->playIndex < oldestHeld->playIndex ||
            (candidate->playIndex == oldestHeld->playIndex &&
             candidate->ampenv.level < oldestHeld->ampenv.level)) {
            oldestHeld = candidate;
        }
    }
    return quietestRelease ? quietestRelease : oldestHeld;
}

static inline void palco_trim_voice_count(tsf* synth, int voiceLimit) {
    int voicesToSteal = palco_active_voice_count(synth) - voiceLimit;
    while (voicesToSteal-- > 0) {
        tsf_voice* voice = palco_voice_to_steal(synth);
        if (!voice) break;
        tsf_voice_kill(voice);
    }
}

// TinySoundFont only reuses voices once its complete pool is full. This helper
// enforces a smaller logical budget when needed and guarantees that a fully
// held chord cannot make newer notes disappear.
static inline void palco_prepare_voice_slots(
        tsf* synth, int channel, int key, float velocity, int voiceLimit) {
    if (!synth || !synth->maxVoiceNum || !synth->channels || velocity <= 0.0f ||
        channel < 0 || channel >= synth->channels->channelNum) {
        return;
    }
    voiceLimit = voiceLimit < 1 ? 1 :
            (voiceLimit > synth->maxVoiceNum ? synth->maxVoiceNum : voiceLimit);

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
    if (requiredSlots > voiceLimit) requiredSlots = voiceLimit;

    int voicesToSteal = palco_active_voice_count(synth) + requiredSlots - voiceLimit;
    while (voicesToSteal-- > 0) {
        tsf_voice* voice = palco_voice_to_steal(synth);
        if (!voice) break;
        tsf_voice_kill(voice);
    }
}
