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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 4101;
    private static final int NOTIFICATION_REQUEST = 4102;

    private Spinner profileSpinner;
    private Switch flashSwitch;
    private TextView status;
    private TextView oracle;
    private TextView audit;
    private TorchController torch;
    private boolean receiverRegistered;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ChaosService.ACTION_EVENT.equals(intent.getAction())) return;
            String state = intent.getStringExtra(ChaosService.EXTRA_EVENT_STATE);
            String line = intent.getStringExtra(ChaosService.EXTRA_ORACLE);
            int sound = intent.getIntExtra(ChaosService.EXTRA_SOUND_NUMBER, 0);
            int volume = intent.getIntExtra(ChaosService.EXTRA_VOLUME_PERCENT, 0);
            oracle.setText(line == null ? "The portal issued no statement." : line);
            status.setText("Event: " + state + (sound > 0 ? " · sound " + sound + " · " + volume + "%" : ""));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        torch = new TorchController(this);
        setContentView(buildUi());
        registerEventReceiver();
        runAudit();
        refreshState();
        requestNotificationPermission();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(14, 10, 24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        TextView title = text("ASS-LIGHT", 34, Color.rgb(166, 91, 229));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = text("Randomized social sound oracle · Android RC1", 14, Color.rgb(92, 207, 178));
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        oracle = text("What do you mean? I did not hear anything.", 22, Color.WHITE);
        oracle.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        oracle.setPadding(0, dp(8), 0, dp(18));
        root.addView(oracle);

        root.addView(label("CHAOS PROFILE"));
        profileSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ChaosProfiles.NAMES);
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(1);
        root.addView(profileSpinner, matchWrap(dp(8)));

        flashSwitch = new Switch(this);
        flashSwitch.setText("Pulse flashlight with sound events");
        flashSwitch.setTextColor(Color.WHITE);
        flashSwitch.setPadding(0, dp(12), 0, dp(12));
        flashSwitch.setEnabled(torch.isAvailable());
        flashSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !hasCameraPermission()) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        });
        root.addView(flashSwitch);

        Button open = button("OPEN THE BROWN PORTAL");
        open.setOnClickListener(v -> openPortal());
        root.addView(open, matchWrap(dp(8)));

        Button gust = button("TEST GUST NOW");
        gust.setOnClickListener(v -> playOnce());
        root.addView(gust, matchWrap(dp(8)));

        Button flashlight = button("FLASHLIGHT TOGGLE");
        flashlight.setEnabled(torch.isAvailable());
        flashlight.setOnClickListener(v -> toggleTorch());
        root.addView(flashlight, matchWrap(dp(8)));

        Button seal = button("SEAL THE PORTAL");
        seal.setOnClickListener(v -> stopPortal());
        root.addView(seal, matchWrap(dp(8)));

        status = text("Portal sealed.", 14, Color.rgb(224, 213, 236));
        status.setPadding(0, dp(15), 0, dp(10));
        root.addView(status);

        TextView warning = text("Flashing-light warning: flashlight effects are optional, disabled when unsupported, and use brief pulses only.", 12, Color.rgb(255, 194, 108));
        root.addView(warning);

        TextView auditTitle = label("SECA / DEVOS BUILD TRUTH");
        auditTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(auditTitle);

        audit = text("Audit pending.", 12, Color.rgb(214, 205, 224));
        audit.setTextIsSelectable(true);
        root.addView(audit);

        Button rerun = button("RERUN IN-APP SECA AUDIT");
        rerun.setOnClickListener(v -> runAudit());
        root.addView(rerun, matchWrap(dp(12)));

        TextView boundary = text("Build-truth boundary: the in-app audit verifies packaged resources, declared permissions, service visibility, and local controls. Physical speaker quality, flashlight hardware, lock-screen continuity, and OEM battery behavior require a real-phone receipt.", 12, Color.rgb(170, 160, 185));
        root.addView(boundary);
        return scroll;
    }

    private void openPortal() {
        if (flashSwitch.isChecked() && !hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            Toast.makeText(this, "Camera permission is required only for flashlight pulses.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = serviceIntent(ChaosService.ACTION_START);
        startVisibleService(intent);
        status.setText("Portal opening. A persistent notification provides the stop control.");
        getSharedPreferences("ass_light", MODE_PRIVATE).edit().putBoolean("running", true).apply();
    }

    private void playOnce() {
        Intent intent = serviceIntent(ChaosService.ACTION_PLAY_ONCE);
        startVisibleService(intent);
        status.setText("Test gust requested. The event receipt will appear here.");
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, ChaosService.class)
            .setAction(action)
            .putExtra(ChaosService.EXTRA_PROFILE, profileSpinner.getSelectedItemPosition())
            .putExtra(ChaosService.EXTRA_FLASH, flashSwitch.isChecked() && hasCameraPermission());
    }

    private void startVisibleService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void stopPortal() {
        startService(new Intent(this, ChaosService.class).setAction(ChaosService.ACTION_STOP));
        torch.setEnabled(false);
        status.setText("Portal sealed. Audio, timers, wake lock, and flashlight were ordered off.");
        getSharedPreferences("ass_light", MODE_PRIVATE).edit().putBoolean("running", false).apply();
    }

    private void toggleTorch() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        boolean changed = torch.toggle();
        status.setText(changed ? (torch.isEnabled() ? "Flashlight on." : "Flashlight off.") : "Flashlight unavailable or busy in another app.");
    }

    private void runAudit() {
        SelfAudit.Report report = SelfAudit.run(this);
        audit.setText(report.toDisplayText());
    }

    private void refreshState() {
        boolean running = getSharedPreferences("ass_light", MODE_PRIVATE).getBoolean("running", false);
        status.setText(running ? "Portal reports active. Use the notification or SEAL THE PORTAL to stop." : "Portal sealed.");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerEventReceiver() {
        IntentFilter filter = new IntentFilter(ChaosService.ACTION_EVENT);
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == CAMERA_REQUEST && (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED)) {
            flashSwitch.setChecked(false);
            Toast.makeText(this, "Flashlight remains off. Sound features still work.", Toast.LENGTH_LONG).show();
        }
        runAudit();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(eventReceiver);
        if (torch != null) torch.setEnabled(false);
        super.onDestroy();
    }

    private TextView label(String value) {
        TextView view = text(value, 12, Color.rgb(166, 91, 229));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
