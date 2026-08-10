package com.dawasafe.app;

import android.app.Application;

import org.json.JSONArray;

/**
 * Application entry point. Deliberately thin.
 *
 * <p>Its whole job is to make sure two things are true before any other
 * component runs, because both have to be in place <em>before</em> an alarm can
 * usefully fire:
 *
 * <ol>
 *   <li><b>The notification channels exist.</b> Posting to a channel that has
 *       not been created is silently dropped on Android 8+. The alarm receiver
 *       creates them too, but doing it here means they also exist the first time
 *       the user opens Settings and looks for them.</li>
 *   <li><b>The reconcile pass is armed.</b> A process can be restarted without a
 *       reboot - by a low-memory kill, or by the user force-stopping the app -
 *       and a force-stop clears every alarm without sending BOOT_COMPLETED.
 *       Re-arming on process start covers that gap.</li>
 * </ol>
 *
 * <p>No heavy work belongs here: this runs on the main thread before the first
 * frame, and anything slow shows up directly as launch time on a cheap phone.
 */
public class DawaSafeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            Notifier.ensureChannels(this);
        } catch (Exception ignored) {
        }

        try {
            JSONArray doses = DoseStore.doses(this);
            if (doses.length() > 0) {
                /* Cheap and idempotent: the PendingIntents reuse the same ids
                   with FLAG_UPDATE_CURRENT, so re-arming an alarm that is
                   already armed is a no-op rather than a duplicate. */
                AlarmScheduler.rearm(this);
            }
        } catch (Exception ignored) {
            // Never block app start over the schedule; MainActivity reschedules
            // from JavaScript a moment later anyway.
        }
    }
}
