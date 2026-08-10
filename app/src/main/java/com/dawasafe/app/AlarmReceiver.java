package com.dawasafe.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONObject;

/**
 * Receives a fired dose alarm and turns it into a notification.
 *
 * <p>Everything here runs on the main thread inside {@code onReceive}, which the
 * system gives roughly ten seconds and an implicit wakelock. That is ample for a
 * SharedPreferences read and a {@code notify()}, and it is deliberately kept
 * that way: handing the work to a background thread or a JobService would mean
 * the wakelock can be released before the notification is posted, and the dose
 * would ring late or not at all on a sleeping phone.
 */
public class AlarmReceiver extends BroadcastReceiver {

    /** Short belt-and-braces wakelock. onReceive already holds one, but on some
     *  OEM builds the screen-on path for a full-screen intent needs a moment
     *  longer than the broadcast is alive. */
    private static final long WAKE_MS = 8000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "dawasafe:alarm");
                wl.setReferenceCounted(false);
                wl.acquire(WAKE_MS);
            }
        } catch (Exception ignored) {
        }

        try {
            Notifier.ensureChannels(app);

            if (AlarmScheduler.ACTION_RECONCILE.equals(action)) {
                /* Housekeeping pass. Re-arming is idempotent - the PendingIntents
                   use FLAG_UPDATE_CURRENT and the same ids - so its only effects
                   are the useful ones: doses that have moved into the arming
                   window get armed, and any alarm the OS quietly dropped while
                   the app was in Doze comes back. */
                AlarmScheduler.rearm(app);
                return;
            }

            final int id = intent.getIntExtra(ActionReceiver.EXTRA_ID, 0);
            if (id == 0) {
                return;
            }

            JSONObject dose = DoseStore.doseById(app, id);
            if (dose == null) {
                /* The dose was deleted, or the store was cleared, between arming
                   and firing. Showing a notification we cannot describe would be
                   worse than showing nothing: the user would get a pill reminder
                   with no medicine name on it. */
                return;
            }

            Notifier.showDose(app, dose);

            /* Re-arm after every fire. This is what makes the schedule
               self-healing: each ring is a free opportunity to confirm the rest
               of the week is still armed, without waiting for the 6-hourly
               reconcile or for the user to open the app. */
            AlarmScheduler.arm(app);

        } catch (Exception e) {
            /* A crash in a BroadcastReceiver is an ANR dialog on the user's lock
               screen at 8am. Swallow, and let the next alarm or the reconcile
               pass recover. */
        } finally {
            if (wl != null && wl.isHeld()) {
                try {
                    wl.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
