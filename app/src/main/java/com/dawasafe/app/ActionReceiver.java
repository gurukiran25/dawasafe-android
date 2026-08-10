package com.dawasafe.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * Handles the Taken and Snooze buttons on the dose notification.
 *
 * <h3>Why this does not write the dose record</h3>
 * It would be easy for this receiver to mark the dose as taken in
 * SharedPreferences and be done. It deliberately does not, because the app's
 * record of a dose is not one value - taking a dose also updates the adherence
 * history and decrements the remaining pill count, and the app has a single
 * function that keeps all three in step. If native wrote its own record, the
 * app would show a dose as taken while the pill count silently drifted, and the
 * "you are running low" warning would stop being true.
 *
 * <p>So the tap is queued verbatim - the app's own tag string, the action, the
 * date - and replayed into the app's existing handler the next time the WebView
 * is alive. The user gets instant feedback from the notification either way; the
 * bookkeeping happens through the one code path that has always owned it.
 */
public class ActionReceiver extends BroadcastReceiver {

    static final String ACTION_TAKEN = "com.dawasafe.app.TAKEN";
    static final String ACTION_SNOOZE = "com.dawasafe.app.SNOOZE";

    static final String EXTRA_TAG = "ds_tag";
    static final String EXTRA_DATE = "ds_date";
    static final String EXTRA_ID = "ds_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String action = intent.getAction() == null ? "" : intent.getAction();
        final String tag = intent.getStringExtra(EXTRA_TAG);
        final String date = intent.getStringExtra(EXTRA_DATE);
        final int id = intent.getIntExtra(EXTRA_ID, 0);

        if (tag == null || tag.length() == 0) {
            return;
        }

        try {
            if (ACTION_TAKEN.equals(action)) {
                DoseStore.queueAction(app, tag, "taken", date);
                Notifier.cancel(app, id);
                /* If the app happens to be open right now, push it straight
                   through so the tick appears on screen immediately instead of
                   waiting for the next resume. */
                MainActivity.deliverIfLive(app);

            } else if (ACTION_SNOOZE.equals(action)) {
                int mins = DoseStore.snoozeMinutes(app);

                /* Queue the snooze for the app as well as re-arming natively.
                   The app records a snooze in its own log - that is how the
                   missed-dose catch-up screen knows this dose was deferred
                   rather than ignored. */
                DoseStore.queueAction(app, tag, "snooze", date);
                AlarmScheduler.snooze(app, id, mins);

                showSnoozeConfirmation(app, id, mins);
                MainActivity.deliverIfLive(app);
            }
        } catch (Exception e) {
            /* Never crash here. The queue write is the important part and it has
               its own guard; anything after it is presentation. */
        }
    }

    /**
     * Replace the alarm notification with a quiet confirmation.
     *
     * <p>Without this the notification simply vanishes, and the user is left
     * unsure whether the tap registered - so they take the dose again, or open
     * the app to check. On the quiet channel, so it does not make a second noise.
     */
    private void showSnoozeConfirmation(Context c, int id, int mins) {
        NotificationManager m = Notifier.nm(c);
        if (m == null) {
            return;
        }
        Notifier.ensureChannels(c);

        long at = System.currentTimeMillis() + (long) mins * 60_000L;
        String when = DateFormat.getTimeFormat(c).format(new Date(at));
        String template = DoseStore.labelSnoozedUntil(c, c.getString(R.string.notif_snoozed_until));
        String text;
        try {
            text = String.format(template, when);
        } catch (Exception e) {
            // A translated template with a bad format specifier must not crash.
            text = c.getString(R.string.notif_snoozed_until, when);
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, Notifier.CHAN_INFO);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_stat_dawasafe)
                .setContentTitle(DoseStore.labelTitle(c, c.getString(R.string.notif_dose_title)))
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);

        try {
            /* Same id as the dose alarm: this replaces the alarm notification
               rather than stacking a second row underneath it. */
            m.notify(id, b.build());
        } catch (SecurityException ignored) {
        }
    }
}
