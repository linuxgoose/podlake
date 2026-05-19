package de.danoeh.antennapod.playback.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import androidx.core.content.ContextCompat;

import de.danoeh.antennapod.playback.base.BuildConfig;

public class CustomMediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return;
        }
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            Intent serviceIntent = new Intent(context, Media3PlaybackService.class);
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND) {
                serviceIntent.setAction(Media3PlaybackService.ACTION_WIDGET_REWIND);
            } else if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                serviceIntent.setAction(Media3PlaybackService.ACTION_WIDGET_FAST_FORWARD);
            } else {
                return;
            }
            try {
                context.startService(serviceIntent);
            } catch (IllegalStateException e) {
                return;
            }
            return;
        }

        Intent serviceIntent = new Intent(MediaButtonReceiver.PLAYBACK_SERVICE_INTENT);
        serviceIntent.setPackage(context.getPackageName());
        serviceIntent.putExtra(MediaButtonReceiver.EXTRA_KEYCODE, event.getKeyCode());
        serviceIntent.putExtra(MediaButtonReceiver.EXTRA_SOURCE, event.getSource());
        serviceIntent.putExtra(MediaButtonReceiver.EXTRA_HARDWAREBUTTON,
                event.getEventTime() > 0 || event.getDownTime() > 0);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
