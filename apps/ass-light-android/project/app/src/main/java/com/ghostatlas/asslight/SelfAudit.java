package com.ghostatlas.asslight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.os.Build;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SelfAudit {
    static final int[] SOUND_RESOURCES = {
        R.raw.fart_01, R.raw.fart_02, R.raw.fart_03, R.raw.fart_04,
        R.raw.fart_05, R.raw.fart_06, R.raw.fart_07, R.raw.fart_08,
        R.raw.fart_09, R.raw.fart_10, R.raw.fart_11, R.raw.fart_12
    };

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.CAMERA
    };

    static Report run(Context context) {
        Report report = new Report();
        report.add("APP_ID", context.getPackageName().equals("com.ghostatlas.asslight.rc"), true,
            context.getPackageName());
        report.add("SOUND_COUNT", SOUND_RESOURCES.length == 12, true,
            SOUND_RESOURCES.length + " packaged sound references");
        report.add("PROFILE_BOUNDS", profilesValid(), true,
            "5 profiles with ordered interval and volume ranges");
        report.add("ORACLE_LIBRARY", ChaosProfiles.ORACLES.length >= 20, false,
            ChaosProfiles.ORACLES.length + " denial/oracle lines");

        auditSounds(context, report);
        auditPermissions(context, report);

        TorchController torch = new TorchController(context);
        report.addPartial("PHYSICAL_TORCH", torch.isAvailable()
            ? "Torch hardware detected; real pulse remains a manual device gate."
            : "No torch detected in this environment; sound operation remains valid.");

        boolean notificationGranted = Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        report.add(notificationGranted ? "NOTIFICATION_PERMISSION" : "NOTIFICATION_PERMISSION", notificationGranted, false,
            notificationGranted ? "Foreground control can be shown." : "User has not granted notification display yet.");

        boolean running = context.getSharedPreferences("ass_light", Context.MODE_PRIVATE).getBoolean("running", false);
        report.add("PORTAL_STATE_RECEIPT", true, false, running ? "ACTIVE" : "SEALED");
        report.addPartial("PHYSICAL_RUNTIME", "Speaker quality, lock-screen continuity, OEM battery behavior, and flashlight pulse require a phone receipt.");
        return report;
    }

    private static void auditSounds(Context context, Report report) {
        List<Long> lengths = new ArrayList<>();
        boolean allWave = true;
        boolean allSized = true;
        for (int i = 0; i < SOUND_RESOURCES.length; i++) {
            try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(SOUND_RESOURCES[i])) {
                if (afd == null) throw new IllegalStateException("missing descriptor");
                long length = afd.getLength();
                lengths.add(length);
                allSized &= length > 4096;
                byte[] header = new byte[12];
                try (FileInputStream input = new FileInputStream(afd.getFileDescriptor())) {
                    if (afd.getStartOffset() > 0) input.getChannel().position(afd.getStartOffset());
                    int read = input.read(header);
                    String riff = read >= 12 ? new String(header, 0, 4, StandardCharsets.US_ASCII) : "";
                    String wave = read >= 12 ? new String(header, 8, 4, StandardCharsets.US_ASCII) : "";
                    allWave &= "RIFF".equals(riff) && "WAVE".equals(wave);
                }
            } catch (Exception error) {
                allWave = false;
                allSized = false;
                lengths.add(0L);
            }
        }
        report.add("WAV_HEADERS", allWave, true, "All packaged sounds expose RIFF/WAVE headers.");
        report.add("WAV_PAYLOADS", allSized, true, "Lengths: " + lengths);
        long distinct = lengths.stream().distinct().count();
        report.add("SOUND_DIVERSITY", distinct >= 10, true, distinct + " distinct payload lengths");
    }

    private static void auditPermissions(Context context, Report report) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES);
            List<String> declared = info.requestedPermissions == null
                ? new ArrayList<>() : Arrays.asList(info.requestedPermissions);
            for (String permission : REQUIRED_PERMISSIONS) {
                report.add("DECLARE_" + permission.substring(permission.lastIndexOf('.') + 1),
                    declared.contains(permission), true, permission);
            }
            if (Build.VERSION.SDK_INT >= 34) {
                report.add("DECLARE_FGS_MEDIA", declared.contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK), true,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK);
            }
            report.add("NO_INTERNET_PERMISSION", !declared.contains(Manifest.permission.INTERNET), true,
                "No network capability declared.");
            boolean servicePresent = info.services != null && Arrays.stream(info.services)
                .anyMatch(service -> service.name.endsWith("ChaosService") && !service.exported);
            report.add("PRIVATE_FOREGROUND_SERVICE", servicePresent, true,
                "ChaosService exists and is not exported.");
        } catch (Exception error) {
            report.add("PACKAGE_CONTRACT", false, true, error.getClass().getSimpleName());
        }
    }

    private static boolean profilesValid() {
        if (ChaosProfiles.NAMES.length != 5
            || ChaosProfiles.MIN_SECONDS.length != 5
            || ChaosProfiles.MAX_SECONDS.length != 5
            || ChaosProfiles.MIN_VOLUME.length != 5
            || ChaosProfiles.MAX_VOLUME.length != 5) return false;
        for (int i = 0; i < 5; i++) {
            if (ChaosProfiles.MIN_SECONDS[i] <= 0
                || ChaosProfiles.MAX_SECONDS[i] < ChaosProfiles.MIN_SECONDS[i]
                || ChaosProfiles.MIN_VOLUME[i] < 0f
                || ChaosProfiles.MAX_VOLUME[i] > 1f
                || ChaosProfiles.MAX_VOLUME[i] < ChaosProfiles.MIN_VOLUME[i]) return false;
        }
        return true;
    }

    static final class Report {
        private final List<Gate> gates = new ArrayList<>();

        void add(String id, boolean pass, boolean critical, String evidence) {
            gates.add(new Gate(id, pass ? "VERIFIED" : (critical ? "FAILED" : "PARTIAL"), critical, evidence));
        }

        void addPartial(String id, String evidence) {
            gates.add(new Gate(id, "PARTIAL", false, evidence));
        }

        boolean hasCriticalFailure() {
            return gates.stream().anyMatch(gate -> gate.critical && "FAILED".equals(gate.state));
        }

        String state() {
            if (hasCriticalFailure()) return "FAILED";
            if (gates.stream().anyMatch(gate -> "PARTIAL".equals(gate.state))) return "PARTIAL";
            return "VERIFIED";
        }

        String toDisplayText() {
            StringBuilder builder = new StringBuilder();
            builder.append("SECA STATE: ").append(state()).append('\n');
            builder.append("No completion claim exceeds the receipts below.\n\n");
            for (Gate gate : gates) {
                builder.append('[').append(gate.state).append("] ")
                    .append(gate.id).append("\n  ")
                    .append(gate.evidence).append("\n");
            }
            return builder.toString();
        }
    }

    private static final class Gate {
        final String id;
        final String state;
        final boolean critical;
        final String evidence;

        Gate(String id, String state, boolean critical, String evidence) {
            this.id = id;
            this.state = state;
            this.critical = critical;
            this.evidence = evidence == null ? "No evidence" : evidence;
        }
    }

    private SelfAudit() {}
}
