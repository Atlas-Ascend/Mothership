package com.ghostatlas.asslight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.security.SecureRandom;

public class ChaosService extends Service {
    public static final String ACTION_START = "com.ghostatlas.asslight.START";
    public static final String ACTION_STOP = "com.ghostatlas.asslight.STOP";
    public static final String ACTION_PLAY_ONCE = "com.ghostatlas.asslight.PLAY_ONCE";
    public static final String ACTION_EVENT = "com.ghostatlas.asslight.EVENT";
    public static final String EXTRA_ORACLE = "oracle";
    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_FLASH = "flash";
    public static final String EXTRA_SOUND_NUMBER = "soundNumber";
    public static final String EXTRA_VOLUME_PERCENT = "volumePercent";
    public static final String EXTRA_EVENT_STATE = "eventState";

    private static final String CHANNEL_ID = "ass_light_portal";
    private static final int NOTIFICATION_ID = 9099;
    private static final long MAX_SESSION_MS = 6L * 60L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private TorchController torch;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean flashWithEvent;
    private int profileIndex = 1;

    private final Runnable scheduledEvent = new Runnable() {
        @Override public void run() {
            if (!running) return;
            playEvent(false);
            scheduleNext();
        }
    };

    private final Runnable sessionTimeout = () -> {
        if (running) {
            broadcastEvent("Six-hour safety seal engaged.", 0, 0, "SESSION_TIMEOUT");
            stopPortal();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        torch = new TorchController(this);
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager != null) {
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ASS-LIGHT:PortalWakeLock");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPortal();
            return START_NOT_STICKY;
        }

        profileIndex = clampProfile(intent.getIntExtra(EXTRA_PROFILE, 1));
        flashWithEvent = intent.getBooleanExtra(EXTRA_FLASH, false);
        startForeground(NOTIFICATION_ID, buildNotification("The atmosphere is awaiting instructions."));

        if (ACTION_PLAY_ONCE.equals(action)) {
            boolean stopAfter = !running;
            playEvent(stopAfter);
            return START_NOT_STICKY;
        }

        running = true;
        getPrefs().edit().putBoolean("running", true).apply();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(MAX_SESSION_MS + 60_000L);
        handler.removeCallbacks(scheduledEvent);
        handler.removeCallbacks(sessionTimeout);
        handler.postDelayed(sessionTimeout, MAX_SESSION_MS);
        scheduleNext();
        updateNotification("The next disturbance is unknowable. Tap SEAL PORTAL to stop.");
        return START_NOT_STICKY;
    }

    private void scheduleNext() {
        int min = ChaosProfiles.MIN_SECONDS[profileIndex];
        int max = ChaosProfiles.MAX_SECONDS[profileIndex];
        int seconds = min + random.nextInt(Math.max(1, max - min + 1));
        handler.postDelayed(scheduledEvent, seconds * 1000L);
    }

    private void playEvent(boolean stopAfter) {
        releasePlayer();
        int soundIndex = random.nextInt(SelfAudit.SOUND_RESOURCES.length);
        int sound = SelfAudit.SOUND_RESOURCES[soundIndex];
        float min = ChaosProfiles.MIN_VOLUME[profileIndex];
        float max = ChaosProfiles.MAX_VOLUME[profileIndex];
        float volume = min + random.nextFloat() * (max - min);
        String oracle = ChaosProfiles.ORACLES[random.nextInt(ChaosProfiles.ORACLES.length)];

        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            android.content.res.AssetFileDescriptor descriptor = getResources().openRawResourceFd(sound);
            if (descriptor == null) throw new IllegalStateException("Missing sound resource");
            player.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
            descriptor.close();
            player.setVolume(volume, volume);
            player.setOnPreparedListener(mediaPlayer -> {
                mediaPlayer.start();
                updateNotification(oracle);
                broadcastEvent(oracle, soundIndex + 1, Math.round(volume * 100), "PLAYBACK_STARTED");
            });
            player.setOnCompletionListener(mediaPlayer -> {
                releasePlayer();
                if (stopAfter) handler.postDelayed(this::stopPortal, 900L);
            });
            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                broadcastEvent("Audio event failed safely. Portal remains controllable.", soundIndex + 1, Math.round(volume * 100), "AUDIO_FAILED");
                releasePlayer();
                if (stopAfter) stopPortal();
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            releasePlayer();
            broadcastEvent("Audio event failed safely. Portal remains controllable.", soundIndex + 1, Math.round(volume * 100), "AUDIO_FAILED");
            if (stopAfter) stopPortal();
            return;
        }

        if (flashWithEvent && torch != null) torch.pulse(180L);
    }

    private void broadcastEvent(String oracle, int soundNumber, int volumePercent, String state) {
        Intent event = new Intent(ACTION_EVENT)
            .setPackage(getPackageName())
            .putExtra(EXTRA_ORACLE, oracle)
            .putExtra(EXTRA_SOUND_NUMBER, soundNumber)
            .putExtra(EXTRA_VOLUME_PERCENT, volumePercent)
            .putExtra(EXTRA_EVENT_STATE, state);
        sendBroadcast(event);
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ChaosService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(running ? "ASS-LIGHT portal open" : "ASS-LIGHT")
            .setContentText(content)
            .setStyle(new Notification.BigTextStyle().bigText(content))
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "SEAL PORTAL", stop).build())
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(content));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "ASS-LIGHT active portal", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible control for randomized ASS-LIGHT sound sessions.");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void stopPortal() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (torch != null) torch.setEnabled(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        getPrefs().edit().putBoolean("running", false).apply();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("ass_light", MODE_PRIVATE);
    }

    private int clampProfile(int value) {
        return Math.max(0, Math.min(ChaosProfiles.NAMES.length - 1, value));
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) {}
            player.reset();
            player.release();
            player = null;
        }
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (torch != null) torch.setEnabled(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        getPrefs().edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
