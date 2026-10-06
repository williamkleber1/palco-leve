package com.wk.palcoleve;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceStatus;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;

final class MidiInputManager {
    interface Listener {
        void onMidiConnection(String name);
        void onNoteOn(int channel, int note, int velocity);
        void onNoteOff(int channel, int note);
        void onControlChange(int channel, int controller, int value);
        void onPitchBend(int channel, int value);
        void onProgramChange(int channel, int program);
    }

    private final MidiManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private MidiDevice device;
    private MidiOutputPort outputPort;
    private int connectedDeviceId = -1;

    private final MidiReceiver receiver = new MidiReceiver() {
        private int runningStatus = -1;
        private int firstData = -1;

        @Override
        public void onSend(byte[] data, int offset, int count, long timestamp) {
            for (int i = offset; i < offset + count; ++i) {
                int value = data[i] & 0xFF;
                if (value >= 0xF8) continue;

                if ((value & 0x80) != 0) {
                    if (value >= 0xF0) {
                        runningStatus = -1;
                    } else {
                        runningStatus = value;
                    }
                    firstData = -1;
                    continue;
                }

                if (runningStatus < 0) continue;
                int command = runningStatus & 0xF0;
                int channel = runningStatus & 0x0F;
                if (command == 0xC0) {
                    listener.onProgramChange(channel, value);
                    continue;
                }
                if (command == 0xD0) continue;

                if (firstData < 0) {
                    firstData = value;
                    continue;
                }

                dispatch(command, channel, firstData, value);
                firstData = -1;
            }
        }
    };

    private final MidiManager.DeviceCallback callback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) { connectIfPossible(info); }

        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            if (info.getId() == connectedDeviceId) {
                closeCurrent();
                listener.onMidiConnection("Nenhum teclado MIDI");
                connectFirstAvailable();
            }
        }

        @Override public void onDeviceStatusChanged(MidiDeviceStatus status) { }
    };

    MidiInputManager(Context context, Listener listener) {
        this.listener = listener;
        this.manager = (MidiManager) context.getSystemService(Context.MIDI_SERVICE);
    }

    void start() {
        if (manager == null) {
            listener.onMidiConnection("MIDI indisponível neste Android");
            return;
        }
        manager.registerDeviceCallback(callback, handler);
        connectFirstAvailable();
    }

    void stop() {
        if (manager != null) manager.unregisterDeviceCallback(callback);
        closeCurrent();
    }

    private void connectFirstAvailable() {
        if (manager == null || device != null) return;
        for (MidiDeviceInfo info : manager.getDevices()) {
            if (connectIfPossible(info)) return;
        }
        listener.onMidiConnection("Nenhum teclado MIDI");
    }

    private boolean connectIfPossible(MidiDeviceInfo info) {
        if (manager == null || device != null || info.getOutputPortCount() < 1) return false;
        manager.openDevice(info, opened -> {
            if (opened == null || device != null) {
                if (opened != null) try { opened.close(); } catch (IOException ignored) { }
                return;
            }
            MidiOutputPort port = opened.openOutputPort(0);
            if (port == null) {
                try { opened.close(); } catch (IOException ignored) { }
                return;
            }
            device = opened;
            outputPort = port;
            connectedDeviceId = info.getId();
            outputPort.connect(receiver);
            listener.onMidiConnection(deviceName(info));
        }, handler);
        return true;
    }

    private String deviceName(MidiDeviceInfo info) {
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        }
        return name == null || name.trim().isEmpty() ? "Teclado MIDI conectado" : name;
    }

    private void dispatch(int command, int channel, int first, int second) {
        switch (command) {
            case 0x80:
                listener.onNoteOff(channel, first);
                break;
            case 0x90:
                if (second == 0) listener.onNoteOff(channel, first);
                else listener.onNoteOn(channel, first, second);
                break;
            case 0xB0:
                listener.onControlChange(channel, first, second);
                break;
            case 0xE0:
                listener.onPitchBend(channel, first | (second << 7));
                break;
            default:
                break;
        }
    }

    private void closeCurrent() {
        if (outputPort != null) {
            outputPort.disconnect(receiver);
            try { outputPort.close(); } catch (IOException ignored) { }
        }
        if (device != null) {
            try { device.close(); } catch (IOException ignored) { }
        }
        outputPort = null;
        device = null;
        connectedDeviceId = -1;
    }
}

