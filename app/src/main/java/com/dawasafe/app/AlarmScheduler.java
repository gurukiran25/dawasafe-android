package com.dawasafe.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hands the dose schedule to {@link AlarmManager} and keeps it honest.
 *
 * <h3>Why one alarm per dose, and not a chain</h3>
 * The tempting design is to arm only the next dose and, when it fires, arm the
 * one after. It is cheap and it survives reboot. It also has a single point of
 * failure: if one link is dropped - a crash in the receiver, a vendor
 * "optimiser" killing the app mid-broadcast, a clock jump - the chain is broken
 * and every remaining dose is silently lost until the user happens to reopen the
 * app. For a medicine reminder that is not an acceptable failure mode, so each
 * dose gets its own independent alarm.
 *
 * <h3>What "exact" actually means here</h3>
 * From Android 12 exact alarms are permission-gated. This class never assumes it
 * has that permission: it tries the exact API, catches the SecurityException,
 * and falls back to {@code setAndAllowWhileIdle}, which still wakes the device
 * from Doze but may fire minutes late. {@link #isExact(Context)} reports which
 * mode is live so the app can tell the user the truth rather than showing a
 * green "alarms are on" card that is only half true.
 */
final class AlarmScheduler {

    /** Upper bound on simultaneously armed alarms. Android 14 caps an app at 500;
     *  60 covers roughly three weeks of a three-dose-a-day regimen, and the
     *  reconcile pass tops the window up as time moves forward. */
    private static final int MAX_ARMED = 60;

    /** Reconcile every 6 hours: prune fired doses, arm newly-in-window ones, and
     *  heal the schedule if the OS dropped alarms while the app was asleep. */
    private static final long RECONCILE_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    static final String ACTION_DOSE = "com.dawasafe.app.DOSE";
    static final String ACTION_RECONCILE = "com.dawasafe.app.RECONCILE";
    static final int RECONCILE_REQ = 0x0DA5;

    private AlarmScheduler() {
    }

    private static AlarmManager am(Context c) {
        return (AlarmManager) c.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    }

    /** True when the OS will honour exact alarm times for this app right now. */
    static boolean isExact(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // pre-Android 12: exact alarms need no permission
        }
        AlarmManager a = am(c);
        try {
            return a != null && a.canScheduleExactAlarms();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Replace the whole schedule with {@code doses}.
     *
     * @param doses array of {@code {id, tag, date, at, title, body}} produced by
     *              the app's own upcoming-dose enumerator. {@code id} is the
     *              app's existing stable hash of {@code date|tag}, reused rather
     *              than recomputed so JavaScript and native never disagree about
     *              which alarm is which.
     * @return number of alarms armed.
     */
    static int replaceAll(Context c, JSONArray doses) {
        cancelArmed(c);
        DoseStore.setDoses(c, doses == null ? new JSONArray() : doses);
        int n = arm(c);
        scheduleReconcile(c);
        return n;
    }

    /** Cancel every alarm we previously armed, and drop their notifications. */
    static void cancelArmed(Context c) {
        AlarmManager a = am(c);
        JSONArray ids = DoseStore.armedIds(c);
        for (int i = 0; i < ids.length(); i++) {
            int id = ids.optInt(i, -1);
            if (id < 0) {
                continue;
            }
            if (a != null) {
                try {
                    a.cancel(dosePendingIntent(c, id, true));
                } catch (Exception ignored) {
                }
            }
        }
        DoseStore.setArmedIds(c, new JSONArray());
    }

    /** Cancel everything, including the stored list and the reconcile pass. */
    static void cancelAll(Context c) {
        cancelArmed(c);
        DoseStore.setDoses(c, new JSONArray());
        AlarmManager a = am(c);
        if (a != null) {
            try {
                a.cancel(reconcilePendingIntent(c, true));
            } catch (Exception ignored) {
            }
        }
    }

    /** Cancel a specific subset by id, leaving the rest armed. */
    static void cancelIds(Context c, JSONArray idsToDrop) {
        if (idsToDrop == null || idsToDrop.length() == 0) {
            return;
        }
        JSONArray keep = new JSONArray();
        JSONArray doses = DoseStore.doses(c);
        for (int i = 0; i < doses.length(); i++) {
            JSONObject o = doses.optJSONObject(i);
            if (o == null) {
                continue;
            }
            boolean drop = false;
            for (int j = 0; j < idsToDrop.length(); j++) {
                if (idsToDrop.optInt(j, -1) == o.optInt("id", -2)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                keep.put(o);
            }
        }
        replaceAll(c, keep);
    }

    /**
     * Arm alarms for every stored dose still in the future, up to
     * {@link #MAX_ARMED}. Doses already in the past are left in the store but
     * not armed - the app's own missed-dose catch-up screen is what asks the
     * user about those, and it must not be pre-empted by a stale alarm ringing
     * hours late.
     */
    static int arm(Context c) {
        AlarmManager a = am(c);
        if (a == null) {
            return 0;
        }
        JSONArray doses = DoseStore.doses(c);
        long now = System.currentTimeMillis();

        // Sort indices by time. The JS side already sorts, but a reconcile after
        // a timezone change can reorder things, and arming out of order would
        // hand setAlarmClock the wrong "soonest" dose.
        int n = doses.length();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> {
            long ax = doses.optJSONObject(x) == null ? 0 : doses.optJSONObject(x).optLong("at", 0);
            long ay = doses.optJSONObject(y) == null ? 0 : doses.optJSONObject(y).optLong("at", 0);
            return Long.compare(ax, ay);
        });

        JSONArray armed = new JSONArray();
        boolean first = true;
        int count = 0;

        for (int k = 0; k < n && count < MAX_ARMED; k++) {
            JSONObject o = doses.optJSONObject(order[k]);
            if (o == null) {
                continue;
            }
            long at = o.optLong("at", 0L);
            int id = o.optInt("id", 0);
            if (at <= now || id == 0) {
                continue;
            }

            PendingIntent pi = dosePendingIntent(c, id, false);
            boolean ok = setOne(a, c, at, pi, first);
            if (ok) {
                armed.put(id);
                count++;
                first = false;
            }
        }

        DoseStore.setArmedIds(c, armed);
        return count;
    }

    /**
     * Arm a single alarm, escalating from most to least reliable.
     *
     * @param soonest when true this is the very next dose, and it is armed with
     *                {@code setAlarmClock}. That API is treated by the OS as a
     *                user-visible alarm clock: it is the least likely to be
     *                deferred, and it puts the alarm icon in the status bar,
     *                which doubles as visible proof to the user that the next
     *                dose really is set.
     */
    private static boolean setOne(AlarmManager a, Context c, long at, PendingIntent pi, boolean soonest) {
        if (soonest) {
            try {
                a.setAlarmClock(new AlarmManager.AlarmClockInfo(at, pi), pi);
                return true;
            } catch (SecurityException | IllegalStateException e) {
                // Exact-alarm permission refused, or the 500-alarm cap was hit.
                // Fall through to the next best option rather than losing the dose.
            } catch (Exception e) {
                // fall through
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                a.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
            return true;
        } catch (SecurityException e) {
            /* Android 12+ with exact alarms revoked. setAndAllowWhileIdle still
               wakes the device out of Doze, but the OS may hold it for a few
               minutes. That is a real difference for a medicine, so it is not
               papered over: isExact() reports false and the app shows the amber
               "timing may drift" banner instead of the green one. */
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    a.set(AlarmManager.RTC_WAKEUP, at, pi);
                }
                return true;
            } catch (Exception e2) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** A repeating, deliberately inexact housekeeping pass. Inexact because its
     *  timing does not matter and an exact repeat would burn battery for nothing. */
    static void scheduleReconcile(Context c) {
        AlarmManager a = am(c);
        if (a == null) {
            return;
        }
        try {
            a.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RECONCILE_INTERVAL_MS,
                    RECONCILE_INTERVAL_MS,
                    reconcilePendingIntent(c, false));
        } catch (Exception ignored) {
        }
    }

    /**
     * PendingIntent for one dose.
     *
     * @param forCancel when true, uses {@code FLAG_NO_CREATE} so cancelling does
     *                  not resurrect a PendingIntent that no longer exists.
     */
    private static PendingIntent dosePendingIntent(Context c, int id, boolean forCancel) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(ACTION_DOSE)
                /* Unique data URI per dose: PendingIntent equality ignores
                   extras, so without this every dose would collide onto one
                   alarm and only the last one scheduled would ever ring. */
                .setData(Uri.parse("dawasafe://alarm/" + id))
                .putExtra(ActionReceiver.EXTRA_ID, id);
        int extra = forCancel ? PendingIntent.FLAG_NO_CREATE : 0;
        return PendingIntent.getBroadcast(c, id, i, Notifier.piFlags(extra));
    }

    private static PendingIntent reconcilePendingIntent(Context c, boolean forCancel) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(ACTION_RECONCILE)
                .setData(Uri.parse("dawasafe://reconcile"));
        int extra = forCancel ? PendingIntent.FLAG_NO_CREATE : 0;
        return PendingIntent.getBroadcast(c, RECONCILE_REQ, i, Notifier.piFlags(extra));
    }

    /** Re-arm from the stored list. Used by BootReceiver and the reconcile pass. */
    static int rearm(Context c) {
        int n = arm(c);
        scheduleReconcile(c);
        return n;
    }

    /** How many stored doses are still in the future - what the app reports as
     *  "reminders are set for the next N doses". */
    static int futureCount(Context c) {
        JSONArray doses = DoseStore.doses(c);
        long now = System.currentTimeMillis();
        int n = 0;
        for (int i = 0; i < doses.length(); i++) {
            JSONObject o = doses.optJSONObject(i);
            if (o != null && o.optLong("at", 0L) > now) {
                n++;
            }
        }
        return n;
    }

    /** Timestamp of the next armed dose, or 0. */
    static long nextAt(Context c) {
        JSONArray doses = DoseStore.doses(c);
        long now = System.currentTimeMillis();
        long best = 0L;
        for (int i = 0; i < doses.length(); i++) {
            JSONObject o = doses.optJSONObject(i);
            if (o == null) {
                continue;
            }
            long at = o.optLong("at", 0L);
            if (at > now && (best == 0L || at < best)) {
                best = at;
            }
        }
        return best;
    }

    /** Arm a snooze: a one-off alarm re-using the dose's own id space so the
     *  snoozed notification replaces rather than duplicates the original. */
    static void snooze(Context c, int id, int minutes) {
        AlarmManager a = am(c);
        if (a == null) {
            return;
        }
        long at = System.currentTimeMillis() + (long) minutes * 60_000L;
        setOne(a, c, at, dosePendingIntent(c, id, false), true);
    }
}
