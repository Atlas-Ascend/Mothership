package com.ghostatlas.asslight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class RuntimeSmokeTest {
    private Activity launchApp(Context context) {
        Intent launch = new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return InstrumentationRegistry.getInstrumentation().startActivitySync(launch);
    }

    @Test
    public void embeddedSoundPreparesAndStartsWithBoundedReceipt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Activity activity = launchApp(context);
        CountDownLatch event = new CountDownLatch(1);
        AtomicInteger sound = new AtomicInteger(0);
        AtomicInteger volume = new AtomicInteger(0);
        AtomicReference<String> state = new AtomicReference<>("");

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                state.set(intent.getStringExtra(ChaosService.EXTRA_EVENT_STATE));
                sound.set(intent.getIntExtra(ChaosService.EXTRA_SOUND_NUMBER, 0));
                volume.set(intent.getIntExtra(ChaosService.EXTRA_VOLUME_PERCENT, 0));
                event.countDown();
            }
        };

        IntentFilter filter = new IntentFilter(ChaosService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        try {
            Intent play = new Intent(context, ChaosService.class)
                .setAction(ChaosService.ACTION_PLAY_ONCE)
                .putExtra(ChaosService.EXTRA_PROFILE, 4)
                .putExtra(ChaosService.EXTRA_FLASH, false);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(play); else context.startService(play);

            assertTrue("No playback receipt arrived", event.await(12, TimeUnit.SECONDS));
            assertEquals("PLAYBACK_STARTED", state.get());
            assertTrue("Sound index outside 1..12", sound.get() >= 1 && sound.get() <= 12);
            assertTrue("Loki volume outside 28..100", volume.get() >= 28 && volume.get() <= 100);
        } finally {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            context.startService(new Intent(context, ChaosService.class).setAction(ChaosService.ACTION_STOP));
            activity.finish();
        }
    }

    @Test
    public void foregroundSessionStartsAndPortalSealClearsRuntimeState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Activity activity = launchApp(context);
        try {
            Intent start = new Intent(context, ChaosService.class)
                .setAction(ChaosService.ACTION_START)
                .putExtra(ChaosService.EXTRA_PROFILE, 0)
                .putExtra(ChaosService.EXTRA_FLASH, false);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start); else context.startService(start);
            Thread.sleep(1200L);
            assertTrue(context.getSharedPreferences("ass_light", Context.MODE_PRIVATE).getBoolean("running", false));

            context.startService(new Intent(context, ChaosService.class).setAction(ChaosService.ACTION_STOP));
            Thread.sleep(1200L);
            assertTrue(!context.getSharedPreferences("ass_light", Context.MODE_PRIVATE).getBoolean("running", true));
        } finally {
            context.stopService(new Intent(context, ChaosService.class));
            activity.finish();
        }
    }
}
