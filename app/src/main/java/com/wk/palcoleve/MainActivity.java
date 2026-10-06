package com.wk.palcoleve;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final int TRACK_COUNT = 5;
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

    private static native int nativeLoadSoundFont(int track, String path);
    private static native String[] nativeGetPresetNames(int track);
    private static native boolean nativeStart();
    private static native void nativeStop();
    private static native void nativeNoteOn(int channel, int note, int velocity);
    private static native void nativeNoteOff(int channel, int note);
    private static native void nativeControlChange(int channel, int controller, int value);
    private static native void nativePitchBend(int channel, int value);
    private static native void nativeSetPreset(int track, int preset);
    private static native void nativeSetVolume(float volume);
    private static native void nativeSetTrackVolume(int track, float volume);
    private static native void nativeSetTrackEnabled(int track, boolean enabled);
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
    private ToggleButton trackToggle;
    private SeekBar trackVolumeSeek;
    private final Button[] trackButtons = new Button[TRACK_COUNT];
    private final LinearLayout[] trackRows = new LinearLayout[TRACK_COUNT];
    private final TextView[] trackBankLabels = new TextView[TRACK_COUNT];
    private final TextView[] trackPresetLabels = new TextView[TRACK_COUNT];
    private final ToggleButton[] trackOnButtons = new ToggleButton[TRACK_COUNT];
    private final SeekBar[] trackLevelBars = new SeekBar[TRACK_COUNT];
    private TextView inspectorTitle;
    private Button audioButton;
    private Button demoButton;
    private Button stressButton;
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
    private boolean refreshingTrackUi;
    private boolean suppressBankSelection;
    private int demoGeneration;
    private int selectedTrack;
    private final List<SoundFontEntry> folderBanks = new ArrayList<>();
    private final String[] trackBankNames = new String[TRACK_COUNT];
    private final String[][] trackPresetNames = new String[TRACK_COUNT][];
    private final int[] trackPresetIndices = new int[TRACK_COUNT];
    private final float[] trackVolumes = { 1f, 1f, 1f, 1f, 1f };
    private final boolean[] trackEnabled = { true, true, true, true, true };

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

        boolean restoredTrackOne = false;
        for (int track = 0; track < TRACK_COUNT; ++track) {
            String path = getPreferences(MODE_PRIVATE)
                    .getString("track_" + track + "_path", null);
            String name = getPreferences(MODE_PRIVATE)
                    .getString("track_" + track + "_name", null);
            if (path != null && new File(path).isFile()) {
                loadBank(new File(path), track, false, name);
                if (track == 0) restoredTrackOne = true;
            }
        }
        String lastPath = getPreferences(MODE_PRIVATE).getString("last_sf2_path", null);
        if (!restoredTrackOne && lastPath != null && new File(lastPath).isFile()) {
            loadBank(new File(lastPath), 0, false, new File(lastPath).getName());
        }
        String folderUri = getPreferences(MODE_PRIVATE).getString("sf2_folder_uri", null);
        if (folderUri != null) scanSoundFontFolder(Uri.parse(folderUri));
    }

    private void buildInterface() {
        LinearLayout root = vertical();
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.setBackgroundColor(BG);

        LinearLayout toolbar = horizontal();
        TextView title = text("PALCO LEVE", 22, PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, fixed(175));

        engineStatus = text("Nenhum SF2 carregado", 11, SECONDARY);
        engineStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.addView(engineStatus, weighted(1));

        Button library = button("BIBLIOTECA", CARD, ACCENT);
        library.setOnClickListener(v -> showLibraryMenu());
        toolbar.addView(library, fixed(130));

        Button panic = button("PÂNICO", Color.rgb(62, 31, 38), DANGER);
        panic.setOnClickListener(v -> nativePanic());
        toolbar.addView(panic, fixed(100));
        root.addView(toolbar, matchWrap());

        LinearLayout statusRow = horizontal();
        midiStatus = pill("●  Procurando teclado MIDI", ACCENT);
        statusRow.addView(midiStatus, weighted(1));
        bankName = pill("0/5 tracks • biblioteca não configurada", SECONDARY);
        bankName.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statusRow.addView(bankName, weighted(1));
        root.addView(statusRow, matchWrap());

        LinearLayout workspace = horizontal();
        LinearLayout trackList = vertical();
        trackList.setPadding(0, dp(2), dp(8), 0);

        for (int i = 0; i < TRACK_COUNT; ++i) {
            final int track = i;
            LinearLayout row = horizontal();
            row.setPadding(dp(8), dp(3), dp(8), dp(3));
            row.setBackground(rounded(CARD, 7));
            row.setClickable(true);
            row.setOnClickListener(v -> showBankPickerForTrack(track));
            trackRows[i] = row;

            View colorStrip = new View(this);
            colorStrip.setBackgroundColor(i == 0 ? ACCENT : Color.rgb(70, 90, 107));
            row.addView(colorStrip, new LinearLayout.LayoutParams(dp(5),
                    LinearLayout.LayoutParams.MATCH_PARENT));

            TextView number = text(String.format("%02d", i + 1), 14, SECONDARY);
            number.setGravity(Gravity.CENTER);
            row.addView(number, fixed(48));

            LinearLayout names = vertical();
            names.setPadding(dp(6), 0, dp(8), 0);
            names.setClickable(true);
            names.setOnClickListener(v -> showBankPickerForTrack(track));
            trackBankLabels[i] = text("Clique para escolher um SoundFont", 14, PRIMARY);
            trackPresetLabels[i] = text("Track vazia", 10, SECONDARY);
            names.addView(trackBankLabels[i], matchWrap());
            names.addView(trackPresetLabels[i], matchWrap());
            row.addView(names, weighted(1.25f));

            trackOnButtons[i] = new ToggleButton(this);
            trackOnButtons[i].setTextOn("ON");
            trackOnButtons[i].setTextOff("MUTE");
            trackOnButtons[i].setTextColor(PRIMARY);
            trackOnButtons[i].setBackground(rounded(Color.rgb(35, 47, 59), 6));
            trackOnButtons[i].setOnCheckedChangeListener((button, checked) -> {
                if (refreshingTrackUi) return;
                trackEnabled[track] = checked;
                nativeSetTrackEnabled(track, checked);
            });
            row.addView(trackOnButtons[i], fixed(68));

            TextView level = text("VOL", 9, SECONDARY);
            level.setGravity(Gravity.CENTER);
            row.addView(level, fixed(34));

            trackLevelBars[i] = new SeekBar(this);
            trackLevelBars[i].setMax(100);
            trackLevelBars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (refreshingTrackUi) return;
                    trackVolumes[track] = progress / 100f;
                    nativeSetTrackVolume(track, trackVolumes[track]);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            row.addView(trackLevelBars[i], weighted(0.7f));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            rowParams.setMargins(0, dp(3), 0, dp(3));
            trackList.addView(row, rowParams);
        }

        LinearLayout inspector = vertical();
        inspector.setPadding(dp(10), dp(8), dp(10), dp(8));
        inspector.setBackground(rounded(Color.rgb(15, 22, 29), 8));

        inspectorTitle = text("TRACK 01  •  INSPECTOR", 13, ACCENT);
        inspector.addView(inspectorTitle, matchWrap());

        TextView presetLabel = text("TIMBRE / PRESET", 9, SECONDARY);
        inspector.addView(presetLabel, matchWrap());
        presetSpinner = new Spinner(this);
        presetSpinner.setBackground(rounded(CARD, 7));
        presetSpinner.setPadding(dp(10), 0, dp(10), 0);
        presetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                  View view, int position, long id) {
                if (presetsReady && !refreshingTrackUi) {
                    trackPresetIndices[selectedTrack] = position;
                    nativeSetPreset(selectedTrack, position);
                    refreshTrackRows();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams presetParams = matchWrap();
        presetParams.height = dp(46);
        inspector.addView(presetSpinner, presetParams);

        LinearLayout transposeRow = horizontal();
        Button down = button("−", CARD, PRIMARY);
        down.setOnClickListener(v -> changeTranspose(-1));
        transposeRow.addView(down, fixed(48));
        transposeValue = pill("TRANSPOSE  0", ACCENT);
        transposeValue.setGravity(Gravity.CENTER);
        transposeRow.addView(transposeValue, weighted(1));
        Button up = button("+", CARD, PRIMARY);
        up.setOnClickListener(v -> changeTranspose(1));
        transposeRow.addView(up, fixed(48));
        inspector.addView(transposeRow, matchWrap());

        LinearLayout masterRow = horizontal();
        TextView masterLabel = text("MASTER", 9, SECONDARY);
        masterLabel.setGravity(Gravity.CENTER);
        masterRow.addView(masterLabel, fixed(62));
        SeekBar master = new SeekBar(this);
        master.setMax(120);
        master.setProgress(80);
        master.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentVolume = progress / 100f;
                nativeSetVolume(currentVolume);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        masterRow.addView(master, weighted(1));
        inspector.addView(masterRow, matchWrap());

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
        inspector.addView(test, matchWrap());

        demoButton = button("OUVIR HINO", CARD, ACCENT);
        demoButton.setOnClickListener(v -> toggleHymnDemo());
        stressButton = button("TESTE COMPLEXO", Color.rgb(33, 42, 60), PRIMARY);
        stressButton.setOnClickListener(v -> toggleComplexDemo());
        LinearLayout demos = horizontal();
        LinearLayout.LayoutParams demoParams = weighted(1);
        demoParams.setMargins(dp(2), dp(4), dp(2), dp(4));
        demos.addView(demoButton, demoParams);
        LinearLayout.LayoutParams stressParams = weighted(1);
        stressParams.setMargins(dp(2), dp(4), dp(2), dp(4));
        demos.addView(stressButton, stressParams);
        inspector.addView(demos, matchWrap());

        audioButton = button("INICIAR ÁUDIO", ACCENT, Color.rgb(7, 35, 29));
        audioButton.setOnClickListener(v -> toggleAudio());
        inspector.addView(audioButton, matchWrap());

        LinearLayout.LayoutParams tracksParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 2.1f);
        LinearLayout.LayoutParams inspectorParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        workspace.addView(trackList, tracksParams);
        workspace.addView(inspector, inspectorParams);
        LinearLayout.LayoutParams workspaceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(workspace, workspaceParams);

        setContentView(root);
        selectTrack(0);
    }

    @SuppressWarnings("unused")
    private void buildLegacyInterface() {
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

        LinearLayout tracks = horizontal();
        TextView tracksLabel = pill("CAMADAS", SECONDARY);
        tracksLabel.setGravity(Gravity.CENTER);
        tracks.addView(tracksLabel, fixed(100));
        for (int i = 0; i < TRACK_COUNT; ++i) {
            final int track = i;
            trackButtons[i] = button((i + 1) + "  +", CARD, PRIMARY);
            trackButtons[i].setOnClickListener(v -> selectTrack(track));
            LinearLayout.LayoutParams params = weighted(1);
            params.setMargins(dp(3), dp(4), dp(3), dp(4));
            tracks.addView(trackButtons[i], params);
        }
        root.addView(tracks, matchWrap());

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
                if (suppressBankSelection) {
                    suppressBankSelection = false;
                    return;
                }
                if (!banksReady || position <= 0 || position > folderBanks.size()) return;
                SoundFontEntry selected = folderBanks.get(position - 1);
                importSoundFont(selected.uri, selected.name, selectedTrack);
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
                if (presetsReady && !refreshingTrackUi) {
                    trackPresetIndices[selectedTrack] = position;
                    nativeSetPreset(selectedTrack, position);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        LinearLayout.LayoutParams spinnerParams = weighted(1);
        spinnerParams.height = dp(48);
        spinnerParams.setMargins(dp(10), dp(5), 0, dp(5));
        presetRow.addView(presetSpinner, spinnerParams);

        trackToggle = new ToggleButton(this);
        trackToggle.setTextOn("CAMADA ON");
        trackToggle.setTextOff("CAMADA OFF");
        trackToggle.setTextColor(PRIMARY);
        trackToggle.setBackground(rounded(CARD, 12));
        trackToggle.setOnCheckedChangeListener((button, checked) -> {
            if (refreshingTrackUi) return;
            trackEnabled[selectedTrack] = checked;
            nativeSetTrackEnabled(selectedTrack, checked);
        });
        presetRow.addView(trackToggle, fixed(120));

        TextView trackVolumeLabel = text("NÍVEL", 10, SECONDARY);
        trackVolumeLabel.setGravity(Gravity.CENTER);
        presetRow.addView(trackVolumeLabel, fixed(45));

        trackVolumeSeek = new SeekBar(this);
        trackVolumeSeek.setMax(100);
        trackVolumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (refreshingTrackUi) return;
                trackVolumes[selectedTrack] = progress / 100f;
                nativeSetTrackVolume(selectedTrack, trackVolumes[selectedTrack]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        presetRow.addView(trackVolumeSeek, weighted(0.45f));
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

        demoButton = button("OUVIR HINO", CARD, ACCENT);
        demoButton.setOnClickListener(v -> toggleHymnDemo());
        performance.addView(demoButton, fixed(150));

        audioButton = button("INICIAR ÁUDIO", ACCENT, Color.rgb(7, 35, 29));
        audioButton.setOnClickListener(v -> toggleAudio());
        performance.addView(audioButton, fixed(190));
        root.addView(performance, matchWrap());

        TextView footer = text(
                "Offline • até 5 SF2 simultâneos • ganho protegido • saída com fio recomendada",
                11, SECONDARY);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, 0);
        root.addView(footer, matchWrap());

        setContentView(root);
        selectTrack(0);
    }

    private void chooseSoundFont() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] { "audio/sf2", "application/octet-stream", "*/*" });
        startActivityForResult(intent, PICK_SF2);
    }

    private void showLibraryMenu() {
        String[] actions = {
                "Escolher pasta de SoundFonts",
                "Importar SF2 avulso para a track " + (selectedTrack + 1)
        };
        new AlertDialog.Builder(this)
                .setTitle("Biblioteca")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) chooseSoundFontFolder();
                    else chooseSoundFont();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showBankPickerForTrack(int track) {
        selectTrack(track);
        if (folderBanks.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Track " + (track + 1))
                    .setMessage("Configure uma pasta de SoundFonts na Biblioteca primeiro.")
                    .setPositiveButton("Abrir Biblioteca", (dialog, which) -> showLibraryMenu())
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        String[] names = new String[folderBanks.size()];
        int checked = -1;
        for (int i = 0; i < folderBanks.size(); ++i) {
            names[i] = folderBanks.get(i).name;
            if (names[i].equals(trackBankNames[track])) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Track " + (track + 1) + " • SoundFont")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    SoundFontEntry selected = folderBanks.get(which);
                    dialog.dismiss();
                    importSoundFont(selected.uri, selected.name, track);
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
            importSoundFont(uri, selectedTrack);
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

    private void importSoundFont(Uri uri, int track) {
        importSoundFont(uri, queryName(uri), track);
    }

    private void importSoundFont(Uri uri, String sourceName, int track) {
        final int targetTrack = track;
        String displayName = sourceName;
        if (!displayName.toLowerCase().endsWith(".sf2")) displayName += ".sf2";
        final String resolvedDisplayName = displayName;
        String safeName = displayName.replaceAll("[^A-Za-z0-9._ -]", "_");
        File directory = getExternalFilesDir("soundfonts");
        if (directory == null) {
            engineStatus.setText("Armazenamento indisponível");
            return;
        }
        File destination = new File(directory, safeName);
        engineStatus.setText("Importando camada " + (targetTrack + 1) + "…");

        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                if (input == null) throw new IllegalStateException("Arquivo indisponível");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                loadBank(destination, targetTrack, true, resolvedDisplayName);
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
        banksReady = true;
        refreshSelectedTrack();
        updateTrackSummary();
    }

    private void loadBank(File file, int track, boolean alreadyOnWorker, String displayName) {
        final String resolvedName = displayName == null ? file.getName() : displayName;
        Runnable task = () -> {
            runOnUiThread(() -> {
                presetsReady = false;
                engineStatus.setText("Carregando camada " + (track + 1) + "…");
            });
            int count = nativeLoadSoundFont(track, file.getAbsolutePath());
            String[] names = count > 0 ? nativeGetPresetNames(track) : new String[0];
            runOnUiThread(() -> {
                if (count <= 0) {
                    engineStatus.setText(nativeGetStatus());
                    return;
                }
                trackBankNames[track] = resolvedName;
                trackPresetNames[track] = names;
                trackPresetIndices[track] = 0;
                nativeSetVolume(currentVolume);
                nativeSetTrackVolume(track, trackVolumes[track]);
                nativeSetTrackEnabled(track, trackEnabled[track]);
                getPreferences(MODE_PRIVATE).edit()
                        .putString("track_" + track + "_path", file.getAbsolutePath())
                        .putString("track_" + track + "_name", resolvedName)
                        .putString("last_sf2_path", track == 0 ? file.getAbsolutePath()
                                : getPreferences(MODE_PRIVATE).getString("last_sf2_path", null))
                        .apply();
                if (selectedTrack == track) refreshSelectedTrack();
                updateTrackSummary();
                startAudio();
            });
        };
        if (alreadyOnWorker) task.run(); else worker.execute(task);
    }

    private void selectTrack(int track) {
        selectedTrack = Math.max(0, Math.min(TRACK_COUNT - 1, track));
        refreshSelectedTrack();
    }

    private void refreshSelectedTrack() {
        refreshingTrackUi = true;
        refreshTrackRows();
        inspectorTitle.setText(String.format("TRACK %02d  •  INSPECTOR", selectedTrack + 1));

        String[] names = trackPresetNames[selectedTrack];
        String[] shown = names == null || names.length == 0
                ? new String[] { "Escolha um SF2 para esta camada" } : names;
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, shown);
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);
        presetsReady = names != null && names.length > 0;
        if (presetsReady) {
            presetSpinner.setSelection(Math.min(trackPresetIndices[selectedTrack], names.length - 1), false);
        }
        refreshingTrackUi = false;
    }

    private void refreshTrackRows() {
        for (int i = 0; i < TRACK_COUNT; ++i) {
            boolean selected = i == selectedTrack;
            trackRows[i].setBackground(rounded(
                    selected ? Color.rgb(27, 53, 60) : CARD, 7));
            String bank = trackBankNames[i];
            trackBankLabels[i].setText(bank == null
                    ? "Clique para escolher um SoundFont" : bank.replace(".sf2", ""));
            String[] presets = trackPresetNames[i];
            if (presets == null || presets.length == 0) {
                trackPresetLabels[i].setText("Track vazia");
            } else {
                int preset = Math.min(trackPresetIndices[i], presets.length - 1);
                trackPresetLabels[i].setText(presets[preset]);
            }
            trackOnButtons[i].setChecked(trackEnabled[i]);
            trackLevelBars[i].setProgress(Math.round(trackVolumes[i] * 100f));
        }
    }

    private void updateTrackSummary() {
        int loaded = 0;
        for (String name : trackBankNames) if (name != null) ++loaded;
        bankName.setText(loaded + "/5 tracks • " + folderBanks.size() + " SoundFonts");
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
        stopHymnDemo();
        nativePanic();
        transpose = Math.max(-24, Math.min(24, transpose + delta));
        transposeValue.setText("TRANSPOSE  " + (transpose > 0 ? "+" : "") + transpose);
    }

    private void toggleHymnDemo() {
        if (demoButton.getText().toString().contains("PARAR")) {
            stopHymnDemo();
            return;
        }
        stopHymnDemo();
        if (!audioRunning) startAudio();
        if (!audioRunning) return;

        nativePanic();
        final int generation = ++demoGeneration;
        demoButton.setText("PARAR HINO");

        // "When the Saints Go Marching In", spiritual tradicional em domínio público.
        final int[] notes = {
                60, 64, 65, 67,
                60, 64, 65, 67,
                60, 64, 65, 67, 64, 60, 64, 62,
                64, 64, 62, 60, 60, 64, 67, 67, 65,
                65, 64, 65, 67, 64, 60, 62, 60
        };
        final int[] beats = {
                1, 1, 1, 4,
                1, 1, 1, 4,
                1, 1, 1, 2, 2, 2, 2, 4,
                2, 1, 1, 2, 1, 1, 2, 1, 4,
                2, 1, 1, 2, 2, 2, 2, 4
        };

        long positionMs = 250L;
        final int beatMs = 300;
        for (int i = 0; i < notes.length; ++i) {
            final int note = notes[i];
            final int velocity = (i == 0 || i == 4 || i == 8) ? 112 : 96;
            final long noteStart = positionMs;
            final long noteLength = Math.max(120L, beats[i] * beatMs - 45L);
            handler.postDelayed(() -> {
                if (generation == demoGeneration) nativeNoteOn(0, note, velocity);
            }, noteStart);
            handler.postDelayed(() -> {
                if (generation == demoGeneration) nativeNoteOff(0, note);
            }, noteStart + noteLength);
            positionMs += (long) beats[i] * beatMs;
        }
        handler.postDelayed(() -> {
            if (generation != demoGeneration) return;
            nativePanic();
            demoButton.setText("OUVIR HINO");
        }, positionMs + 600L);
    }

    private void toggleComplexDemo() {
        if (stressButton.getText().toString().contains("PARAR")) {
            stopHymnDemo();
            return;
        }
        stopHymnDemo();
        if (!audioRunning) startAudio();
        if (!audioRunning) return;

        final int generation = ++demoGeneration;
        stressButton.setText("PARAR TESTE");
        nativePanic();

        // Arranjo gospel original: acordes, baixo, arpejo e melodia concorrentes.
        final int[][] chords = {
                {48, 55, 59, 62, 64}, // Cmaj9
                {45, 52, 55, 59, 60}, // Am9
                {41, 48, 52, 55, 57}, // Fmaj9
                {43, 50, 55, 60, 62}, // Gsus4
                {48, 55, 59, 62, 64},
                {45, 52, 55, 59, 64}, // Am11
                {41, 48, 52, 57, 60}, // Fmaj9/A
                {43, 50, 55, 59, 62}  // G
        };
        final int[][] arpeggios = {
                {60, 64, 67, 71, 72, 71, 67, 64},
                {57, 60, 64, 67, 69, 67, 64, 60},
                {53, 57, 60, 64, 65, 64, 60, 57},
                {55, 60, 62, 67, 69, 67, 62, 60},
                {60, 64, 67, 71, 74, 71, 67, 64},
                {57, 60, 64, 67, 72, 69, 67, 64},
                {53, 57, 60, 64, 69, 64, 60, 57},
                {55, 59, 62, 67, 71, 67, 62, 59}
        };
        final int[][] melodies = {
                {76, 79, 81, 79},
                {76, 72, 74, 76},
                {77, 76, 74, 72},
                {74, 79, 77, 74},
                {76, 79, 84, 83},
                {81, 79, 76, 74},
                {77, 81, 79, 76},
                {74, 71, 72, 72}
        };

        final long startMs = 300L;
        final int eighthMs = 300;
        final int barMs = eighthMs * 8;
        for (int bar = 0; bar < chords.length; ++bar) {
            long barStart = startMs + (long) bar * barMs;
            for (int note : chords[bar]) {
                scheduleDemoNote(generation, note, 52, barStart, barMs - 90L);
            }
            for (int step = 0; step < arpeggios[bar].length; ++step) {
                scheduleDemoNote(generation, arpeggios[bar][step], 66,
                        barStart + (long) step * eighthMs, eighthMs - 45L);
            }
            for (int beat = 0; beat < melodies[bar].length; ++beat) {
                scheduleDemoNote(generation, melodies[bar][beat],
                        beat == 0 ? 102 : 90,
                        barStart + (long) beat * eighthMs * 2,
                        eighthMs * 2 - 70L);
            }
        }

        long ending = startMs + (long) chords.length * barMs;
        for (int note : new int[] {48, 55, 60, 64, 67, 72}) {
            scheduleDemoNote(generation, note, 76, ending, 1800L);
        }
        handler.postDelayed(() -> {
            if (generation != demoGeneration) return;
            nativePanic();
            stressButton.setText("TESTE COMPLEXO");
        }, ending + 2100L);
    }

    private void scheduleDemoNote(int generation, int note, int velocity,
                                  long startMs, long durationMs) {
        handler.postDelayed(() -> {
            if (generation == demoGeneration) nativeNoteOn(0, note, velocity);
        }, startMs);
        handler.postDelayed(() -> {
            if (generation == demoGeneration) nativeNoteOff(0, note);
        }, startMs + durationMs);
    }

    private void stopHymnDemo() {
        ++demoGeneration;
        nativePanic();
        if (demoButton != null) demoButton.setText("OUVIR HINO");
        if (stressButton != null) stressButton.setText("TESTE COMPLEXO");
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
        stopHymnDemo();
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
