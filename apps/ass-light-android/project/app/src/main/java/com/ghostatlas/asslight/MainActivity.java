package com.ghostatlas.asslight;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 401;
    private static final int REQUEST_CAMERA = 402;

    private Spinner profileSpinner;
    private Switch flashEvents;
    private Button portalButton;
    private Button torchButton;
    private TextView status;
    private TextView oracle;
    private TextView auditSummary;
    private TorchController torch;
    private boolean receiverRegistered;

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ChaosService.ACTION_EVENT.equals(intent.getAction())) return;
            String state = intent.getStringExtra(ChaosService.EXTRA_EVENT_STATE);
            String message = intent.getStringExtra(ChaosService.EXTRA_ORACLE);
            int sound = intent.getIntExtra(ChaosService.EXTRA_SOUND_NUMBER, 0);
            int volume = intent.getIntExtra(ChaosService.EXTRA_VOLUME_PERCENT, 0);
            if (message != null) oracle.setText("“" + message + "”");
            status.setText((state == null ? "EVENT" : state) +
                (sound > 0 ? " · sound " + sound + " · " + volume + "%" : ""));
            syncPortalState();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        torch = new TorchController(this);
        setContentView(buildInterface());
        runPreflight(false);
        syncPortalState();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ChaosService.ACTION_EVENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(eventReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        syncPortalState();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(eventReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (torch != null) torch.setEnabled(false);
        super.onDestroy();
    }

    private View buildInterface() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(28), pad, dp(36));
        content.setBackgroundColor(Color.rgb(14, 10, 24));

        TextView title = text("ASS-LIGHT", 34, Color.rgb(166, 91, 229), true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, matchWrap());

        TextView subtitle = text("A pocket trickster for consensual tension-breaking.\nThe atmosphere has been compromised. Allegedly.", 16, Color.LTGRAY, false);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        content.addView(subtitle, matchWrap());

        content.addView(text("CHAOS CURRENT", 13, Color.rgb(92, 207, 178), true), matchWrap());
        profileSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ChaosProfiles.NAMES) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setTextSize(17);
                view.setPadding(dp(10), dp(10), dp(10), dp(10));
                return view;
            }
        };
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(1);
        content.addView(profileSpinner, matchWrap());

        flashEvents = new Switch(this);
        flashEvents.setText("Flash once with each event (180 ms)");
        flashEvents.setTextColor(Color.WHITE);
        flashEvents.setTextSize(15);
        flashEvents.setChecked(false);
        flashEvents.setPadding(0, dp(12), 0, dp(8));
        flashEvents.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !torch.isAvailable()) {
                button.setChecked(false);
                toast("This device has no usable rear flashlight. Sound mode still works.");
            } else if (checked && !cameraGranted()) {
                button.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            }
        });
        content.addView(flashEvents, matchWrap());

        portalButton = button("OPEN THE BROWN PORTAL", Color.rgb(166, 91, 229));
        portalButton.setOnClickListener(v -> {
            if (isPortalRunning()) sealPortal(); else openPortal();
        });
        content.addView(portalButton, matchWrapWithTop(14));

        Button gust = button("TEST GUST", Color.rgb(92, 207, 178));
        gust.setTextColor(Color.rgb(14, 10, 24));
        gust.setOnClickListener(v -> startSound(true));
        content.addView(gust, matchWrapWithTop(10));

        torchButton = button("FLASHLIGHT: OFF", Color.rgb(48, 42, 64));
        torchButton.setOnClickListener(v -> toggleTorch());
        content.addView(torchButton, matchWrapWithTop(10));

        Button audit = button("RUN SECA PREFLIGHT", Color.rgb(48, 42, 64));
        audit.setOnClickListener(v -> runPreflight(true));
        content.addView(audit, matchWrapWithTop(10));

        status = text("Portal sealed.", 15, Color.rgb(92, 207, 178), true);
        status.setPadding(0, dp(22), 0, dp(8));
        content.addView(status, matchWrap());

        oracle = text("“What do you mean? I didn’t hear anything.”", 20, Color.WHITE, false);
        oracle.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        oracle.setGravity(Gravity.CENTER_HORIZONTAL);
        oracle.setPadding(0, dp(8), 0, dp(18));
        content.addView(oracle, matchWrap());

        auditSummary = text("SECA has not run yet.", 13, Color.LTGRAY, false);
        content.addView(auditSummary, matchWrap());

        TextView boundary = text(
            "BUILD-TRUTH BOUNDARY\nNo covert startup. No internet. No tracking. Flash mode is off by default. Active sessions remain visible and stoppable. Use with people who share the joke. Physical flashlight, speaker quality, lock-screen timing, and OEM battery behavior require real-phone proof.",
            12, Color.GRAY, false);
        boundary.setPadding(0, dp(22), 0, 0);
        content.addView(boundary, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private void openPortal() {
        if (!notificationGranted()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            status.setText("Notification permission is required so the active portal stays visible and stoppable.");
            return;
        }
        SelfAudit.Result result = runPreflight(false);
        if (!result.criticalPass()) {
            status.setText("SECA BLOCKED portal start. Run preflight for the receipt.");
            return;
        }
        startSound(false);
    }

    private void startSound(boolean once) {
        if (!notificationGranted()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        Intent intent = new Intent(this, ChaosService.class)
            .setAction(once ? ChaosService.ACTION_PLAY_ONCE : ChaosService.ACTION_START)
            .putExtra(ChaosService.EXTRA_PROFILE, profileSpinner.getSelectedItemPosition())
            .putExtra(ChaosService.EXTRA_FLASH, flashEvents.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText(once ? "Test gust dispatched. Awaiting playback receipt…" : "Portal opening…");
        getSharedPreferences("ass_light", MODE_PRIVATE).edit().putBoolean("running", !once).apply();
        syncPortalState();
    }

    private void sealPortal() {
        startService(new Intent(this, ChaosService.class).setAction(ChaosService.ACTION_STOP));
        getSharedPreferences("ass_light", MODE_PRIVATE).edit().putBoolean("running", false).apply();
        if (torch != null) torch.setEnabled(false);
        flashEvents.setChecked(false);
        status.setText("Portal sealed. The allegations remain.");
        syncPortalState();
    }

    private void toggleTorch() {
        if (!torch.isAvailable()) {
            toast("No rear flashlight was detected.");
            return;
        }
        if (!cameraGranted()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        boolean changed = torch.toggle();
        if (!changed) toast("The flashlight is busy or unavailable.");
        torchButton.setText(torch.isEnabled() ? "FLASHLIGHT: ON" : "FLASHLIGHT: OFF");
    }

    private SelfAudit.Result runPreflight(boolean showDialog) {
        SelfAudit.Result result = SelfAudit.run(this);
        auditSummary.setText("SECA " + result.summary());
        auditSummary.setTextColor(result.criticalPass() ? Color.rgb(92, 207, 178) : Color.rgb(255, 120, 120));
        if (showDialog) {
            new AlertDialog.Builder(this)
                .setTitle("SECA PREFLIGHT · " + result.summary())
                .setMessage(result.detail())
                .setPositiveButton("ACKNOWLEDGE", null)
                .show();
        }
        return result;
    }

    private void syncPortalState() {
        boolean running = isPortalRunning();
        portalButton.setText(running ? "SEAL THE PORTAL" : "OPEN THE BROWN PORTAL");
        if (running) status.setText("Portal open · visible notification active.");
        torchButton.setText(torch != null && torch.isEnabled() ? "FLASHLIGHT: ON" : "FLASHLIGHT: OFF");
    }

    private boolean isPortalRunning() {
        return getSharedPreferences("ass_light", MODE_PRIVATE).getBoolean("running", false);
    }

    private boolean notificationGranted() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean cameraGranted() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            status.setText(granted ? "Notification visibility enabled. Portal may now open." : "Portal start remains blocked without visible notifications.");
            runPreflight(false);
        } else if (requestCode == REQUEST_CAMERA) {
            status.setText(granted ? "Flashlight permission enabled. Flash mode remains off until selected." : "Flashlight disabled. Sound mode remains available.");
            runPreflight(false);
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, int background) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackgroundColor(background);
        button.setMinHeight(dp(54));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
