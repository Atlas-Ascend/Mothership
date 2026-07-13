package com.ghostatlas.asslight;

import android.Manifest;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SelfAudit {
    static final int[] SOUND_RESOURCES = {
        R.raw.fart_01, R.raw.fart_02, R.raw.fart_03, R.raw.fart_04,
        R.raw.fart_05, R.raw.fart_06, R.raw.fart_07, R.raw.fart_08,
        R.raw.fart_09, R.raw.fart_10, R.raw.fart_11, R.raw.fart_12
    };

    static final class Check {
        final String id;
        final String label;
        final boolean passed;
        final boolean required;
        final String evidence;

        Check(String id, String label, boolean passed, boolean required, String evidence) {
            this.id = id;
            this.label = label;
            this.passed = passed;
            this.required = required;
            this.evidence = evidence;
        }
    }

    static final class Result {
        final List<Check> checks;

        Result(List<Check> checks) { this.checks = checks; }

        boolean criticalPass() {
            for (Check check : checks) {
                if (check.required && !check.passed) return false;
            }
            return true;
        }

        int passedCount() {
            int count = 0;
            for (Check check : checks) if (check.passed) count++;
            return count;
        }

        String summary() {
            return (criticalPass() ? "PASS" : "BLOCKED") + " · " + passedCount() + "/" + checks.size() + " checks";
        }

        String detail() {
            StringBuilder out = new StringBuilder();
            for (Check check : checks) {
                out.append(check.passed ? "✓ " : (check.required ? "✕ " : "△ "))
                    .append(check.label).append(" · ").append(check.evidence).append('\n');
            }
            return out.toString().trim();
        }
    }

    private SelfAudit() {}

    static Result run(Context context) {
        List<Check> checks = new ArrayList<>();
        checks.add(checkSoundLibrary(context));
        checks.add(checkForegroundService(context));
        checks.add(checkNoInternetPermission(context));
        checks.add(checkNotificationVisibility(context));
        checks.add(checkTargetSdk(context));
        checks.add(checkTorch(context));
        return new Result(checks);
    }

    private static Check checkSoundLibrary(Context context) {
        int valid = 0;
        long totalBytes = 0;
        for (int resource : SOUND_RESOURCES) {
            try (AssetFileDescriptor descriptor = context.getResources().openRawResourceFd(resource)) {
                if (descriptor != null && descriptor.getLength() > 44) {
                    valid++;
                    totalBytes += descriptor.getLength();
                }
            } catch (Exception ignored) {}
        }
        return new Check(
            "SOUND_LIBRARY",
            "Original sound library",
            valid == SOUND_RESOURCES.length,
            true,
            valid + "/" + SOUND_RESOURCES.length + " readable WAV resources · " + totalBytes + " bytes"
        );
    }

    private static Check checkForegroundService(Context context) {
        try {
            ComponentName name = new ComponentName(context, ChaosService.class);
            ServiceInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = context.getPackageManager().getServiceInfo(name, PackageManager.ComponentInfoFlags.of(0));
            } else {
                info = context.getPackageManager().getServiceInfo(name, 0);
            }
            boolean privateService = !info.exported;
            boolean mediaPlayback = Build.VERSION.SDK_INT < 29
                || (info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0;
            return new Check(
                "FOREGROUND_SERVICE",
                "Visible background engine",
                privateService && mediaPlayback,
                true,
                "exported=" + info.exported + " · mediaPlayback=" + mediaPlayback
            );
        } catch (Exception error) {
            return new Check("FOREGROUND_SERVICE", "Visible background engine", false, true, error.getClass().getSimpleName());
        }
    }

    private static Check checkNoInternetPermission(Context context) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            } else {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            }
            Set<String> permissions = new HashSet<>();
            if (info.requestedPermissions != null) permissions.addAll(Arrays.asList(info.requestedPermissions));
            boolean noInternet = !permissions.contains(Manifest.permission.INTERNET);
            return new Check(
                "NO_NETWORK",
                "No network or tracking channel",
                noInternet,
                true,
                noInternet ? "INTERNET permission absent" : "INTERNET permission present"
            );
        } catch (Exception error) {
            return new Check("NO_NETWORK", "No network or tracking channel", false, true, error.getClass().getSimpleName());
        }
    }

    private static Check checkNotificationVisibility(Context context) {
        boolean granted = Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean enabled = Build.VERSION.SDK_INT < 24 || (manager != null && manager.areNotificationsEnabled());
        return new Check(
            "NOTIFICATION_VISIBILITY",
            "User-visible active-session notice",
            granted && enabled,
            true,
            "permission=" + granted + " · notificationsEnabled=" + enabled
        );
    }

    private static Check checkTargetSdk(Context context) {
        int target = context.getApplicationInfo().targetSdkVersion;
        return new Check("TARGET_SDK", "Current Android target", target >= 35, true, "targetSdk=" + target);
    }

    private static Check checkTorch(Context context) {
        TorchController torch = new TorchController(context);
        boolean available = torch.isAvailable();
        return new Check(
            "TORCH_CAPABILITY",
            "Flashlight hardware",
            available,
            false,
            available ? "rear torch detected" : "not available on this device; sound mode remains functional"
        );
    }
}
