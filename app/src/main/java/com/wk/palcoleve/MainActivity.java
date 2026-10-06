package com.wk.palcoleve;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements MidiInputManager.Listener {
    private static final int PICK_SF2 = 1001;
    private static final int PICK_SF2_FOLDER = 1002;
    private static final int BG = Color.rgb(11, 16, 22);
    private static final int CARD = Color.rgb(21, 29, 38);
    private static final int PRIMARY = Color.rgb(244, 248, 250);
    private static final int SECONDARY = Color.rgb(156, 171, 187);
    private static final int ACCENT = Color.rgb(102, 227, 196);
    private static final int DANGER = Color.rgb(255, 112, 128);

    static {
        System.loadLibrary("palcoleve");
    }

    private static native int nativeLoadSoundFont(String path);
    private static native String[] nativeGetPresetNames();
    private static native boolean nativeStart();
    private static native void nativeStop();
    private static native void nativeNoteOn(int channel, int note, int velocity);
    private static native void nativeNoteOff(int channel, int note);
    private static native void nativeControlChange(int channel, int controller, int value);
    private static native void nativePitchBend(int channel, int value);
    private static native void nativeSetPreset(int preset);
    private static native void nativeSetVolume(float volume);
    private static native void nativePanic();
    private static native String nativeGetStatus();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView engineStatus;
    private TextView midiStatus;
    private TextView bankName;
    private TextView transposeValue;
    private Spinner bankSpinner;
    private Spinner presetSpinner;
    private Button audioButton;
    private MidiInputManager midiInput;
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            nativePanic();
        }
    };
    private int transpose;
    private float currentVolume = 0.8f;
    private boolean audioRunning;
    private boolean banksReady;
    private boolean presetsReady;
    private final List<SoundFontEntry> folderBanks = new ArrayList<>();

    private final Runnable statusUpdater = new Runnable() {
        @Override public void run() {
            engineStatus.setText(nativeGetStatus());
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 15));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 15));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        buildInterface();
        midiInput = new MidiInputManager(this, this);
        midiInput.start();
        handler.post(statusUpdater);

        String lastPath = getPreferences(MODE_PRIVATE).getString("last_sf2_path", null);
        if (lastPath != null && new File(lastPath).isFile()) {
            loadBank(new File(lastPath), false);
        }
        String folderUri = getPreferences(MODE_PRIVATE).getString("sf2_folder_uri", null);
        if (folderUri != null) scanSoundFontFolder(Uri.parse(folderUri));
    }

    private void buildInterface() {
        LinearLayout root = vertical();
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.setBackgroundColor(BG);

        LinearLayout header = horizontal();
        TextView title = text("PALCO LEVE", 25, PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, weighted(1));
        engineStatus = text("Nenhum SF2 carregado", 12, SECONDARY);
        engineStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(engineStatus, weighted(2));
        root.addView(header, matchWrap());

        LinearLayout connection = horizontal();
        midiStatus = pill("●  Procurando teclado MIDI", ACCENT);
        connection.addView(midiStatus, weighted(1));
        bankName = pill("Banco: nenhum", SECONDARY);
        bankName.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        connection.addView(bankName, weighted(1));
        root.addView(connection, matchWrap());

        LinearLayout bankRow = horizontal();
        Button load = button("SF2 avulso", ACCENT, Color.rgb(7, 35, 29));
        load.setOnClickListener(v -> chooseSoundFont());
        bankRow.addView(load, fixed(135));

        Button folder = button("Pasta SF2", CARD, PRIMARY);
        folder.setOnClickListener(v -> chooseSoundFontFolder());
        bankRow.addView(folder, fixed(135));

        bankSpinner = new Spinner(this);
        bankSpinner.setBackground(rounded(CARD, 12));
        bankSpinner.setPadding(dp(14), 0, dp(14), 0);
        bankSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                  View view, int position, long id) {
                if (!banksReady || position <= 0 || position > folderBanks.size()) return;
                SoundFontEntry selected = folderBanks.get(position - 1);
                importSoundFont(selected.uri, selected.name);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        ArrayAdapter<String> emptyBanks = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[] { "Selecione uma pasta de SF2" });
        emptyBanks.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bankSpinner.setAdapter(emptyBanks);
        LinearLayout.LayoutParams bankParams = weighted(1);
        bankParams.height = dp(48);
        bankParams.setMargins(dp(8), dp(5), 0, dp(5));
        bankRow.addView(bankSpinner, bankParams);
        root.addView(bankRow, matchWrap());

        LinearLayout presetRow = horizontal();
        TextView presetLabel = pill("TIMBRE", SECONDARY);
        presetLabel.setGravity(Gravity.CENTER);
        presetRow.addView(presetLabel, fixed(100));
        presetSpinner = new Spinner(this);
        presetSpinner.setBackground(rounded(CARD, 12));
        presetSpinner.setPadding(dp(14), 0, dp(14), 0);
        presetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                  View view, int position, long id) {
                if (presetsReady) nativeSetPreset(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams spinnerParams = weighted(1);
        spinnerParams.height = dp(48);
        spinnerParams.setMargins(dp(10), dp(5), 0, dp(5));
        presetRow.addView(presetSpinner, spinnerParams);
        root.addView(presetRow, matchWrap());

        LinearLayout controls = horizontal();
        Button down = button("−", CARD, PRIMARY);
        down.setTextSize(25);
        down.setOnClickListener(v -> changeTranspose(-1));
        controls.addView(down, fixed(54));

        transposeValue = pill("TRANSPOSE  0", ACCENT);
        transposeValue.setGravity(Gravity.CENTER);
        controls.addView(transposeValue, fixed(150));

        Button up = button("+", CARD, PRIMARY);
        up.setTextSize(23);
        up.setOnClickListener(v -> changeTranspose(1));
        controls.addView(up, fixed(54));

        ToggleButton sustain = new ToggleButton(this);
        sustain.setTextOn("SUSTAIN  ON");
        sustain.setTextOff("SUSTAIN  OFF");
        sustain.setText("SUSTAIN  OFF");
        sustain.setTextColor(PRIMARY);
        sustain.setBackground(rounded(CARD, 12));
        sustain.setOnCheckedChangeListener((button, checked) ->
                nativeControlChange(0, 64, checked ? 127 : 0));
        controls.addView(sustain, fixed(150));

        Button panic = button("PÂNICO", Color.rgb(62, 31, 38), DANGER);
        panic.setOnClickListener(v -> nativePanic());
        controls.addView(panic, fixed(120));

        TextView volumeLabel = text("VOL", 11, SECONDARY);
        volumeLabel.setGravity(Gravity.CENTER);
        controls.addView(volumeLabel, fixed(45));

        SeekBar volume = new SeekBar(this);
        volume.setMax(120);
        volume.setProgress(80);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentVolume = progress / 100f;
                nativeSetVolume(currentVolume);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        controls.addView(volume, weighted(1));
        root.addView(controls, matchWrap());

        LinearLayout performance = horizontal();
        Button test = button("SEGURE PARA TESTAR C4", CARD, PRIMARY);
        test.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                nativeNoteOn(0, clampMidi(60 + transpose), 105);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                nativeNoteOff(0, clampMidi(60 + transpose));
                view.performClick();
                return true;
            }
            return true;
        });
        performance.addView(test, weighted(1));

        audioButton = button("INICIAR ÁUDIO", ACCENT, Color.rgb(7, 35, 29));
        audioButton.setOnClickListener(v -> toggleAudio());
        performance.addView(audioButton, fixed(190));
        root.addView(performance, matchWrap());

        TextView footer = text(
                "Offline • um SF2 por vez • saída com fio recomendada • sem gravação",
                11, SECONDARY);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, 0);
        root.addView(footer, matchWrap());

        setContentView(root);
    }

    private void chooseSoundFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] { "audio/sf2", "application/octet-stream", "*/*" });
        startActivityForResult(intent, PICK_SF2);
    }

    private void chooseSoundFontFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_SF2_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == PICK_SF2) {
            importSoundFont(uri);
        } else if (requestCode == PICK_SF2_FOLDER) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) { }
            getPreferences(MODE_PRIVATE).edit()
                    .putString("sf2_folder_uri", uri.toString()).apply();
            scanSoundFontFolder(uri);
        }
    }

    private void importSoundFont(Uri uri) {
        importSoundFont(uri, queryName(uri));
    }

    private void importSoundFont(Uri uri, String sourceName) {
        String displayName = sourceName;
        if (!displayName.toLowerCase().endsWith(".sf2")) displayName += ".sf2";
        String safeName = displayName.replaceAll("[^A-Za-z0-9._ -]", "_");
        File directory = getExternalFilesDir("soundfonts");
        if (directory == null) {
            engineStatus.setText("Armazenamento indisponível");
            return;
        }
        File destination = new File(directory, safeName);
        engineStatus.setText("Importando " + displayName + "…");

        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                if (input == null) throw new IllegalStateException("Arquivo indisponível");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                loadBank(destination, true);
            } catch (Exception error) {
                runOnUiThread(() -> engineStatus.setText("Falha ao importar: " + error.getMessage()));
            }
        });
    }

    private void scanSoundFontFolder(Uri treeUri) {
        banksReady = false;
        bankName.setText("Lendo pasta de SF2…");
        worker.execute(() -> {
            List<SoundFontEntry> found = new ArrayList<>();
            try {
                String treeId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
                String[] columns = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                };
                try (Cursor cursor = getContentResolver().query(
                        children, columns, null, null,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            String id = cursor.getString(0);
                            String name = cursor.getString(1);
                            String mime = cursor.getString(2);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)
                                    || name == null || !name.toLowerCase().endsWith(".sf2")) {
                                continue;
                            }
                            Uri document = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                            found.add(new SoundFontEntry(name, document));
                        }
                    }
                }
                found.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
                runOnUiThread(() -> showFolderBanks(found));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    banksReady = false;
                    bankName.setText("Pasta SF2 indisponível");
                    engineStatus.setText("Escolha a pasta novamente");
                });
            }
        });
    }

    private void showFolderBanks(List<SoundFontEntry> found) {
        folderBanks.clear();
        folderBanks.addAll(found);
        List<String> labels = new ArrayList<>();
        labels.add(found.isEmpty() ? "Nenhum SF2 nesta pasta" : "Escolha um banco (" + found.size() + ")");
        for (SoundFontEntry entry : found) labels.add(entry.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bankSpinner.setAdapter(adapter);
        banksReady = true;
        bankName.setText(found.size() + " bancos disponíveis");
    }

    private void loadBank(File file, boolean alreadyOnWorker) {
        Runnable task = () -> {
            runOnUiThread(() -> {
                presetsReady = false;
                bankName.setText("Banco: " + file.getName());
                engineStatus.setText("Carregando SF2…");
            });
            int count = nativeLoadSoundFont(file.getAbsolutePath());
            String[] names = count > 0 ? nativeGetPresetNames() : new String[0];
            runOnUiThread(() -> {
                if (count <= 0) {
                    engineStatus.setText(nativeGetStatus());
                    return;
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                presetSpinner.setAdapter(adapter);
                presetsReady = true;
                nativeSetVolume(currentVolume);
                getPreferences(MODE_PRIVATE).edit()
                        .putString("last_sf2_path", file.getAbsolutePath()).apply();
                startAudio();
            });
        };
        if (alreadyOnWorker) task.run(); else worker.execute(task);
    }

    private void toggleAudio() {
        if (audioRunning) {
            nativePanic();
            nativeStop();
            abandonAudioFocus();
            audioRunning = false;
            audioButton.setText("INICIAR ÁUDIO");
        } else {
            startAudio();
        }
    }

    private void startAudio() {
        requestAudioFocus();
        audioRunning = nativeStart();
        audioButton.setText(audioRunning ? "PARAR ÁUDIO" : "INICIAR ÁUDIO");
        engineStatus.setText(nativeGetStatus());
    }

    private void changeTranspose(int delta) {
        nativePanic();
        transpose = Math.max(-24, Math.min(24, transpose + delta));
        transposeValue.setText("TRANSPOSE  " + (transpose > 0 ? "+" : "") + transpose);
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "soundfont-" + System.currentTimeMillis() + ".sf2";
    }

    @Override public void onMidiConnection(String name) {
        runOnUiThread(() -> midiStatus.setText("●  " + name));
    }

    @Override public void onNoteOn(int channel, int note, int velocity) {
        nativeNoteOn(channel, clampMidi(note + transpose), velocity);
    }

    @Override public void onNoteOff(int channel, int note) {
        nativeNoteOff(channel, clampMidi(note + transpose));
    }

    @Override public void onControlChange(int channel, int controller, int value) {
        nativeControlChange(channel, controller, value);
    }

    @Override public void onPitchBend(int channel, int value) {
        nativePitchBend(channel, value);
    }

    @Override public void onProgramChange(int channel, int program) {
        if (presetsReady && program < presetSpinner.getCount()) {
            runOnUiThread(() -> presetSpinner.setSelection(program));
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (midiInput != null) midiInput.stop();
        nativePanic();
        nativeStop();
        worker.shutdownNow();
        abandonAudioFocus();
        super.onDestroy();
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView pill(String value, int color) {
        TextView view = text(value, 12, color);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(rounded(CARD, 10));
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setMinHeight(dp(48));
        button.setBackground(rounded(background, 12));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams fixed(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        return params;
    }

    private int clampMidi(int value) { return Math.max(0, Math.min(127, value)); }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SoundFontEntry {
        final String name;
        final Uri uri;

        SoundFontEntry(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }
}
