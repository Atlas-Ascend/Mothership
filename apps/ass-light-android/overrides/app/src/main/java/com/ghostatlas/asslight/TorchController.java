package com.ghostatlas.asslight;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

final class TorchController {
    private final Context context;
    private final CameraManager manager;
    private String cameraId;
    private boolean enabled;

    TorchController(Context context) {
        this.context = context.getApplicationContext();
        manager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        cameraId = findTorchCamera();
    }

    boolean isAvailable() { return cameraId != null; }
    boolean isEnabled() { return enabled; }

    boolean toggle() {
        if (!isAvailable()) return false;
        return setEnabled(!enabled);
    }

    boolean setEnabled(boolean value) {
        if (!isAvailable()) return false;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            enabled = false;
            return false;
        }
        try {
            manager.setTorchMode(cameraId, value);
            enabled = value;
            return true;
        } catch (CameraAccessException | SecurityException ignored) {
            enabled = false;
            return false;
        }
    }

    void pulse(long milliseconds) {
        if (!isAvailable() || enabled) return;
        if (!setEnabled(true)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> setEnabled(false), milliseconds);
    }

    private String findTorchCamera() {
        if (manager == null) return null;
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    return id;
                }
            }
        } catch (CameraAccessException | SecurityException ignored) {}
        return null;
    }
}
