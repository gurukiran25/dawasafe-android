package com.dawasafe.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Puts the schedule back after the OS has thrown it away.
 *
 * <p>{@code AlarmManager} keeps alarms in memory only. Every one of them is gone
 * after a reboot, and there is no callback that says "your alarms were deleted" -
 * the app simply goes quiet, and the first the user knows about it is a missed
 * dose. That is the entire reason {@link DoseStore} exists: this receiver runs
 * with no Activity, no WebView and no JavaScript, so it needs a native copy of
 * the schedule to re-arm from.
 *
 * <p>The set of triggers is wider than just BOOT_COMPLETED, because there are
 * several other ways to lose alarms:
 *
 * <ul>
 *   <li><b>MY_PACKAGE_REPLACED</b> - an app update also clears them.</li>
 *   <li><b>QUICKBOOT_POWERON</b> - some HTC and Xiaomi builds send this instead
 *       of BOOT_COMPLETED when "fast boot" is on.</li>
 *   <li><b>TIME_SET / TIMEZONE_CHANGED</b> - see below.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String action = intent.getAction() == null ? "" : intent.getAction();

        try {
            Notifier.ensureChannels(app);

            if (Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                /* The stored dose times are absolute instants, computed by the
                   app in the timezone that was current when they were scheduled.
                   After the user flies from Bengaluru to London, "8:00 AM" should
                   still mean 8am where they now are - but the stored instant
                   means 8am IST, which is 2:30am local.

                   Native cannot recompute this: it has the instants, not the
                   dosing rules that produced them. So it flags the schedule as
                   stale. The JavaScript layer checks the flag on next open and
                   rebuilds the whole schedule from the user's actual dose times.

                   The alarms are still re-armed below in the meantime. A dose
                   that rings at the old local time is wrong, but a dose that
                   does not ring at all is worse. */
                DoseStore.setNeedsResync(app, true);
            }

            int n = AlarmScheduler.rearm(app);

            /* Nothing is shown to the user on success. A "reminders restored"
               notification after every reboot would train them to dismiss
               DawaSafe notifications without reading - exactly the habit that
               makes them miss a real dose. */
            if (n == 0) {
                // No future doses stored: either the user has no schedule, or
                // this is a fresh install. Either way there is nothing to say.
                return;
            }

        } catch (Exception e) {
            /* Boot is the worst possible moment to crash: the system is busy,
               and a crash loop here would show a dialog before the user has even
               unlocked the phone. */
        }
    }
}
