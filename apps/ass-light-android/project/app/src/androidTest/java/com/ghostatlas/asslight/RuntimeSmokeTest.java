package com.ghostatlas.asslight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class RuntimeSmokeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After public void cleanup() throws Exception {
        context.startService(new Intent(context, ChaosService.class).setAction(ChaosService.ACTION_STOP));
        Thread.sleep(500L);
    }

    @Test public void allTwelveOriginalSoundsOpenAsRealPayloads() throws Exception {
        assertTrue(SelfAudit.SOUND_RESOURCES.length == 12);
        Set<Long> lengths = new HashSet<>();
        for (int resource : SelfAudit.SOUND_RESOURCES) {
            try (AssetFileDescriptor descriptor = context.getResources().openRawResourceFd(resource)) {
                assertTrue(descriptor != null);
                assertTrue(descriptor.getLength() > 4096L);
                lengths.add(descriptor.getLength());
            }
        }
        assertTrue("Sound payloads must be materially diverse", lengths.size() >= 10);
    }

    @Test public void selfAuditHasNoCriticalFailure() {
        SelfAudit.Report report = SelfAudit.run(context);
        Log.i("ASSLIGHT_SECA", report.toDisplayText());
        assertFalse(report.hasCriticalFailure());
    }

    @Test public void playOnceProducesMediaPlayerStartupReceipt() throws Exception {
        CountDownLatch receipt = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String state = intent.getStringExtra(ChaosService.EXTRA_EVENT_STATE);
                if ("PLAYBACK_STARTED".equals(state)) {
                    int sound = intent.getIntExtra(ChaosService.EXTRA_SOUND_NUMBER, 0);
                    int volume = intent.getIntExtra(ChaosService.EXTRA_VOLUME_PERCENT, 0);
                    Log.i("ASSLIGHT_AUDIO_RECEIPT", "sound=" + sound + ", volume=" + volume + ", state=" + state);
                    if (sound >= 1 && sound <= 12 && volume >= 0 && volume <= 100) receipt.countDown();
                }
            }
        };
        register(receiver);
        try {
            Intent play = new Intent(context, ChaosService.class)
                .setAction(ChaosService.ACTION_PLAY_ONCE)
                .putExtra(ChaosService.EXTRA_PROFILE, 4)
                .putExtra(ChaosService.EXTRA_FLASH, false);
            startVisibleService(play);
            assertTrue("MediaPlayer did not produce a startup receipt", receipt.await(15, TimeUnit.SECONDS));
        } finally {
            context.unregisterReceiver(receiver);
        }
    }

    @Test public void foregroundPortalStartsAndSealCleansState() throws Exception {
        Intent start = new Intent(context, ChaosService.class)
            .setAction(ChaosService.ACTION_START)
            .putExtra(ChaosService.EXTRA_PROFILE, 4)
            .putExtra(ChaosService.EXTRA_FLASH, false);
        startVisibleService(start);
        Thread.sleep(1200L);
        assertTrue(context.getSharedPreferences("ass_light", Context.MODE_PRIVATE).getBoolean("running", false));

        context.startService(new Intent(context, ChaosService.class).setAction(ChaosService.ACTION_STOP));
        Thread.sleep(900L);
        assertFalse(context.getSharedPreferences("ass_light", Context.MODE_PRIVATE).getBoolean("running", true));
    }

    private void register(BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(ChaosService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
    }

    private void startVisibleService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }
}
