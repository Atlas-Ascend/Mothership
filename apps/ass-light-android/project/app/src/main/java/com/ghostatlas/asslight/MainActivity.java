package com.ghostatlas.asslight;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 41;
    private static final int REQUEST_CAMERA = 42;

    private final int background = Color.rgb(14, 10, 24);
    private final int panel = Color.rgb(29, 22, 43);
    private final int purple = Color.rgb(166, 91, 229);
    private final int mint = Color.rgb(92, 207, 178);

    private Spinner profileSpinner;
    private CheckBox pulseCheck;
    private TextView statusText;
    private TextView oracleText;
    private TextView auditText;
    private Button torchButton;
    private TorchController torch;
    private boolean receiverRegistered;
    private boolean cameraRequestForToggle;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(ChaosService.EXTRA_EVENT_STATE);
            String oracle = intent.getStringExtra(ChaosService.EXTRA_ORACLE);
            int sound = intent.getIntExtra(ChaosService.EXTRA_SOUND_NUMBER, 0);
            int volume = intent.getIntExtra(ChaosService.EXTRA_VOLUME_PERCENT, 0);
            if (oracle != null) oracleText.setText('“' + oracle + '”');
            if ("PLAYBACK_STARTED".equals(state)) {
                statusText.setText("Event verified · sound " + sound + " · app volume " + volume + "%");
            } else if (state != null) {
                statusText.setText(state.replace('_', ' ') + " · control remains available");
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        torch = new TorchController(this);
        setContentView(buildInterface());
        requestNotificationPermissionIfNeeded();
        runPreflight();
    }

    private View buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(40));
        scroll.addView(root);

        root.addView(text("A POCKET TRICKSTER · ANDROID RC1", 13, mint, true));
        TextView title = text("ASS-LIGHT", 38, Color.WHITE, true);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);
        TextView subtitle = text("Open the portal. Break the tension. Deny the atmosphere.", 17, Color.LTGRAY, false);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        LinearLayout definition = panel();
        definition.addView(text("BUILD-TRUTH DEFINITION", 13, mint, true));
        definition.addView(text(
            "User-started randomized comedy sounds with bounded intervals and app-local volume. " +
            "Flash mode is off by default. No covert startup, network access, recording, tracking, sponsors, or system-volume mutation.",
            15, Color.WHITE, false));
        root.addView(definition);

        root.addView(section("CHAOS PROFILE"));
        profileSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ChaosProfiles.NAMES) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setTextSize(17);
                view.setPadding(dp(12), dp(12), dp(12), dp(12));
                return view;
            }
        };
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(1);
        root.addView(profileSpinner, matchWrap());

        pulseCheck = new CheckBox(this);
        pulseCheck.setText("Allow one 180 ms flashlight pulse with some sound events");
        pulseCheck.setTextColor(Color.WHITE);
        pulseCheck.setTextSize(15);
        pulseCheck.setChecked(false);
        pulseCheck.setPadding(0, dp(12), 0, dp(8));
        pulseCheck.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !torch.isAvailable()) {
                button.setChecked(false);
                statusText.setText("No rear flashlight detected. Sound mode remains fully available.");
            } else if (checked && !cameraGranted()) {
                button.setChecked(false);
                cameraRequestForToggle = false;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
        });
        root.addView(pulseCheck);

        Button open = actionButton("OPEN THE BROWN PORTAL", purple);
        open.setOnClickListener(v -> openPortal());
        root.addView(open, matchWrapWithTop(12));
        Button test = actionButton("TEST GUST", Color.rgb(64, 52, 86));
        test.setOnClickListener(v -> playOnce());
        root.addView(test, matchWrapWithTop(10));
        Button seal = actionButton("SEAL THE PORTAL", Color.rgb(112, 43, 66));
        seal.setOnClickListener(v -> sealPortal());
        root.addView(seal, matchWrapWithTop(10));
        torchButton = actionButton(torch.isAvailable() ? "FLASHLIGHT · OFF" : "FLASHLIGHT · NOT AVAILABLE", Color.rgb(45, 104, 94));
        torchButton.setEnabled(torch.isAvailable());
        torchButton.setOnClickListener(v -> toggleTorch());
        root.addView(torchButton, matchWrapWithTop(10));

        statusText = text("Portal sealed. The allegations remain.", 15, mint, true);
        statusText.setPadding(0, dp(18), 0, dp(8));
        root.addView(statusText);
        oracleText = text("“What do you mean? I didn’t hear anything.”", 21, Color.WHITE, false);
        oracleText.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        root.addView(oracleText);

        root.addView(section("SECA PREFLIGHT"));
        Button audit = actionButton("RUN SECA PREFLIGHT", Color.rgb(48, 73, 112));
        audit.setOnClickListener(v -> runPreflight());
        root.addView(audit, matchWrap());
        auditText = text("Audit has not run.", 14, Color.LTGRAY, false);
        auditText.setPadding(0, dp(12), 0, dp(8));
        root.addView(auditText);

        TextView safety = text(
            "Use for consensual comedy and tension-breaking. Keep volume appropriate for the room. " +
            "Do not use near emergencies, drivers, sleeping strangers, classrooms, medical settings, or anyone who has asked you to stop. " +
            "No covert startup. The active portal stays visible in Android notifications and always includes SEAL PORTAL.",
            13, Color.GRAY, false);
        safety.setPadding(0, dp(18), 0, 0);
        root.addView(safety);
        return scroll;
    }

    private void openPortal() {
        SelfAudit.Result audit = SelfAudit.run(this);
        auditText.setText(audit.summary() + "\n" + audit.detail());
        if (!audit.criticalPass()) {
            statusText.setText("SECA BLOCKED · resolve required checks before opening the portal.");
            return;
        }
        startChaosService(serviceIntent(ChaosService.ACTION_START));
        statusText.setText("Portal open · " + selectedProfile() + " · persistent stop control active");
    }

    private void playOnce() {
        SelfAudit.Result audit = SelfAudit.run(this);
        auditText.setText(audit.summary() + "\n" + audit.detail());
        if (!audit.criticalPass()) {
            statusText.setText("SECA BLOCKED · Test Gust requires visible notification permission.");
            return;
        }
        startChaosService(serviceIntent(ChaosService.ACTION_PLAY_ONCE));
        statusText.setText("Test Gust armed. The trickster is preparing audio.");
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, ChaosService.class)
            .setAction(action)
            .putExtra(ChaosService.EXTRA_PROFILE, profileSpinner.getSelectedItemPosition())
            .putExtra(ChaosService.EXTRA_FLASH, pulseCheck.isChecked() && cameraGranted());
    }

    private void startChaosService(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void sealPortal() {
        startService(new Intent(this, ChaosService.class).setAction(ChaosService.ACTION_STOP));
        if (torch != null) torch.setEnabled(false);
        updateTorchButton();
        statusText.setText("Portal sealed. The allegations remain.");
    }

    private void toggleTorch() {
        if (!cameraGranted()) {
            cameraRequestForToggle = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        boolean changed = torch.toggle();
        updateTorchButton();
        statusText.setText(changed ? (torch.isEnabled() ? "Flashlight on · manual control" : "Flashlight off") : "Flashlight request failed safely");
    }

    private void updateTorchButton() {
        if (torch == null || !torch.isAvailable()) {
            torchButton.setText("FLASHLIGHT · NOT AVAILABLE");
            torchButton.setEnabled(false);
        } else {
            torchButton.setText(torch.isEnabled() ? "FLASHLIGHT · ON" : "FLASHLIGHT · OFF");
        }
    }

    private void runPreflight() {
        if (auditText == null) return;
        SelfAudit.Result result = SelfAudit.run(this);
        auditText.setText(result.summary() + "\n" + result.detail());
        auditText.setTextColor(result.criticalPass() ? mint : Color.rgb(255, 180, 120));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private boolean cameraGranted() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            statusText.setText(granted ? "Notification visibility granted. SECA can authorize sessions." : "Notifications denied. SECA blocks background portal sessions.");
            runPreflight();
        } else if (requestCode == REQUEST_CAMERA) {
            if (granted && cameraRequestForToggle) toggleTorch();
            else statusText.setText(granted ? "Flash permission granted. Flash mode remains off until selected." : "Camera permission denied. Sound mode remains available.");
            cameraRequestForToggle = false;
            runPreflight();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ChaosService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(eventReceiver, filter);
        receiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (statusText != null && getSharedPreferences("ass_light", MODE_PRIVATE).getBoolean("running", false)) {
            statusText.setText("Portal active · Android notification contains SEAL PORTAL");
        }
        updateTorchButton();
        runPreflight();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(eventReceiver);
            receiverRegistered = false;
        }
        if (torch != null && torch.isEnabled()) torch.setEnabled(false);
        super.onStop();
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackgroundColor(panel);
        return layout;
    }

    private TextView section(String value) {
        TextView view = text(value, 13, mint, true);
        view.setPadding(0, dp(24), 0, dp(8));
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button actionButton(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(color);
        button.setMinHeight(dp(54));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String selectedProfile() {
        int index = profileSpinner.getSelectedItemPosition();
        return ChaosProfiles.NAMES[Math.max(0, Math.min(index, ChaosProfiles.NAMES.length - 1))];
    }
}
