package com.dawasafe.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The full-screen dose alarm, shown over the lock screen.
 *
 * <p>This exists because a notification alone is not enough for the job. On a
 * phone face-down on a bedside table, a heads-up notification is a strip that
 * disappears after a few seconds and leaves a silent icon in the status bar. A
 * full-screen intent behaves like an alarm clock: the screen lights up, the
 * medicine name is readable across a room, and the two answers are large enough
 * to hit without glasses.
 *
 * <h3>Sound is played here, not left to the notification</h3>
 * The channel does have an alarm sound, but a notification sound plays once and
 * stops - about two seconds. Someone asleep, or in another room, will miss it.
 * This activity loops the alarm tone until it is answered, with a hard stop
 * after {@link #AUTO_STOP_MS} so a phone left on a table does not ring forever
 * and train the user to silence the app.
 */
public class AlarmActivity extends Activity {

    static final String EXTRA_TITLE = "ds_title";
    static final String EXTRA_BODY = "ds_body";

    /** Stop ringing after two minutes. The notification stays, so the dose is
     *  not lost - only the noise stops. */
    private static final long AUTO_STOP_MS = 120_000L;

    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoStop;

    private String tag = "";
    private String date = "";
    private int doseId = 0;
    private boolean answered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_alarm);

        readIntent(getIntent());
        bindViews();
        startRinging();
    }

    /** singleTop + this: a second dose firing while the first is on screen
     *  updates this window instead of stacking a second alarm behind it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        answered = false;
        readIntent(intent);
        bindViews();
        startRinging();
    }

    private void readIntent(Intent i) {
        if (i == null) {
            return;
        }
        tag = i.getStringExtra(MainActivity.EXTRA_TAG);
        date = i.getStringExtra(MainActivity.EXTRA_DATE);
        doseId = i.getIntExtra(ActionReceiver.EXTRA_ID, 0);
        if (tag == null) tag = "";
        if (date == null) date = "";
    }

    /**
     * Turn the screen on and show over the keyguard.
     *
     * <p>The flags were deprecated in API 27 in favour of the setter methods,
     * but they are still the only thing that works below 27 - and minSdk here is
     * 21. Both paths are used, which is harmless: on modern releases the setters
     * take effect and the flags are ignored.
     */
    @SuppressWarnings("deprecation")
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                /* Ask the system to dismiss a non-secure keyguard. On a
                   PIN-protected phone this correctly does nothing - the alarm
                   shows over the lock screen but the phone stays locked, which
                   is the right trade: a medicine list is health information and
                   should not be readable by anyone who picks up the handset. */
                km.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void bindViews() {
        TextView clock = findViewById(R.id.alarm_clock);
        TextView title = findViewById(R.id.alarm_title);
        TextView body = findViewById(R.id.alarm_body);
        Button taken = findViewById(R.id.alarm_btn_taken);
        Button snooze = findViewById(R.id.alarm_btn_snooze);

        Intent i = getIntent();
        String t = i == null ? null : i.getStringExtra(EXTRA_TITLE);
        String b = i == null ? null : i.getStringExtra(EXTRA_BODY);

        /* Fall back to the stored dose if the extras are missing - which happens
           when the OS recreates the activity after a configuration change. */
        if (b == null || b.length() == 0) {
            org.json.JSONObject d = DoseStore.doseById(this, doseId);
            if (d != null) {
                if (t == null || t.length() == 0) t = d.optString("title", "");
                b = d.optString("body", "");
            }
        }

        clock.setText(timeNow());
        if (t != null && t.length() > 0) {
            title.setText(t);
        }
        body.setText(b == null ? "" : b);

        taken.setText(DoseStore.labelTaken(this, getString(R.string.alarm_taken)));
        snooze.setText(DoseStore.labelSnooze(this, getString(R.string.alarm_snooze)));

        taken.setOnClickListener(v -> answer("taken"));
        snooze.setOnClickListener(v -> answer("snooze"));
    }

    private String timeNow() {
        try {
            String pattern = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
            return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
        } catch (Exception e) {
            return "";
        }
    }

    private void answer(String act) {
        if (answered) {
            return; // guard against a double tap queueing two "taken" records
        }
        answered = true;
        stopRinging();

        DoseStore.queueAction(this, tag, act, date);
        if ("snooze".equals(act)) {
            AlarmScheduler.snooze(this, doseId, DoseStore.snoozeMinutes(this));
        }
        Notifier.cancel(this, doseId);
        MainActivity.deliverIfLive(getApplicationContext());
        finish();
    }

    /* ------------------------------------------------------------- ringing */

    private void startRinging() {
        stopRinging();
        try {
            Uri uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri != null) {
                player = new MediaPlayer();
                player.setDataSource(this, uri);
                /* USAGE_ALARM again: follows the alarm volume, which people
                   leave up, rather than the notification volume, which they
                   often do not. */
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setLooping(true);
                player.prepare();
                player.start();
            }
        } catch (Exception e) {
            player = null; // a missing ringtone must not stop the alarm showing
        }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 500, 400, 500, 400, 700, 900};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
        }

        autoStop = this::stopRinging;
        handler.postDelayed(autoStop, AUTO_STOP_MS);
    }

    private void stopRinging() {
        if (autoStop != null) {
            handler.removeCallbacks(autoStop);
            autoStop = null;
        }
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
    }

    /**
     * Back button: stop the noise and close, but do NOT record an answer.
     *
     * <p>The notification deliberately survives. Dismissing the alarm screen is
     * not the same as saying the dose was taken, and the app's missed-dose
     * catch-up must still be able to ask about it later.
     */
    @Override
    public void onBackPressed() {
        stopRinging();
        finish();
    }

    /**
     * Stop the noise as soon as this window is no longer visible - the user
     * pressed Home, or a call came in.
     *
     * <p>Deliberately {@code onStop} and not {@code onPause}: a looping alarm
     * still playing while the user is in another app is the behaviour that gets
     * an app uninstalled, and a MediaPlayer owned by a stopped Activity has no
     * business holding the audio focus.
     *
     * <p>The dose is <em>not</em> marked answered. The notification stays in the
     * shade, so the reminder is still there to act on - only the sound stops.
     */
    @Override
    protected void onStop() {
        stopRinging();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopRinging();
        super.onDestroy();
    }
}
