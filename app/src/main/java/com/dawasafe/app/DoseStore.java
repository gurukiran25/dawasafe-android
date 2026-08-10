package com.dawasafe.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything the native side has to remember when the WebView is not running.
 *
 * <p>This class exists because of one hard constraint: {@code AlarmManager}
 * forgets every alarm on reboot, and {@code BootReceiver} has to re-arm them
 * without a WebView, without JavaScript, and without the user opening the app.
 * So the dose schedule cannot live only in {@code localStorage} - native needs
 * its own copy.
 *
 * <p>Three things are stored:
 *
 * <ol>
 *   <li><b>doses</b> - the upcoming dose list pushed down by JavaScript. This is
 *       the authoritative list for arming alarms.</li>
 *   <li><b>actions</b> - a queue of Taken / Snooze taps that happened while the
 *       app was closed. Native must never write a dose record itself: only the
 *       app's own {@code setRemLog()} keeps {@code remlog}, {@code adherence}
 *       and the pill inventory in step. So the tap is queued here verbatim and
 *       replayed into the app the next time it opens.</li>
 *   <li><b>state mirror</b> - a copy of the app's {@code localStorage}. A pure
 *       safety net: if the WebView's storage is ever cleared by the OS or an
 *       aggressive "cleaner" app, the medicine list is not gone.</li>
 * </ol>
 */
final class DoseStore {

    private static final String PREFS = "dawasafe_native";

    private static final String K_DOSES = "doses";
    private static final String K_ARMED = "armed_ids";
    private static final String K_ACTIONS = "pending_actions";
    private static final String K_SNOOZE = "snooze_minutes";
    private static final String K_LBL_TAKEN = "label_taken";
    private static final String K_LBL_SNOOZE = "label_snooze";
    private static final String K_LBL_TITLE = "label_title";
    private static final String K_LBL_SNOOZED = "label_snoozed_until";
    private static final String K_MIRROR = "state_mirror";
    private static final String K_MIRROR_AT = "state_mirror_at";
    /** Set when native knows the stored dose instants no longer match the user's
     *  intended local times - currently only after a clock or timezone change.
     *  Only JavaScript can fix it, so native just raises the flag. */
    private static final String K_RESYNC = "needs_resync";

    /** Cap on queued actions. A queue that grows without bound after months of
     *  an uninstalled-but-not-cleared app would be replayed all at once. */
    private static final int MAX_ACTIONS = 400;

    private static final Object LOCK = new Object();

    private DoseStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------------------------------------------------------------- doses */

    static JSONArray doses(Context c) {
        synchronized (LOCK) {
            try {
                return new JSONArray(sp(c).getString(K_DOSES, "[]"));
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    static void setDoses(Context c, JSONArray arr) {
        synchronized (LOCK) {
            sp(c).edit().putString(K_DOSES, arr == null ? "[]" : arr.toString()).apply();
        }
    }

    /** Ids currently handed to AlarmManager, so a re-schedule can cancel the
     *  ones that disappeared instead of leaving orphan alarms that would ring
     *  for a medicine the user already deleted. */
    static JSONArray armedIds(Context c) {
        synchronized (LOCK) {
            try {
                return new JSONArray(sp(c).getString(K_ARMED, "[]"));
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    static void setArmedIds(Context c, JSONArray ids) {
        synchronized (LOCK) {
            sp(c).edit().putString(K_ARMED, ids == null ? "[]" : ids.toString()).apply();
        }
    }

    /** Look a dose up by the alarm id, so a fired alarm can rebuild its own
     *  notification text without needing the Intent to carry it. */
    static JSONObject doseById(Context c, int id) {
        JSONArray a = doses(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optInt("id", -1) == id) {
                return o;
            }
        }
        return null;
    }

    /* -------------------------------------------------------------- actions */

    /**
     * Queue a notification-button tap for replay into the app.
     *
     * @param tag the app's own {@code "profileId|medId|HH:MM"} identifier - kept
     *            verbatim so the JavaScript side can route it through the
     *            existing {@code handleAction()} path without parsing anything
     *            native invented.
     * @param act one of {@code taken}, {@code snooze}, {@code open}.
     */
    static void queueAction(Context c, String tag, String act, String date) {
        synchronized (LOCK) {
            try {
                JSONArray a = new JSONArray(sp(c).getString(K_ACTIONS, "[]"));
                JSONObject o = new JSONObject();
                o.put("tag", tag);
                o.put("act", act);
                o.put("date", date == null ? "" : date);
                o.put("at", System.currentTimeMillis());
                a.put(o);
                while (a.length() > MAX_ACTIONS) {
                    a.remove(0);
                }
                sp(c).edit().putString(K_ACTIONS, a.toString()).apply();
            } catch (Exception ignored) {
                // A failed queue write must not crash a BroadcastReceiver that
                // is mid-way through showing a dose alarm.
            }
        }
    }

    /**
     * Hand the queue to JavaScript and clear it in the same locked step.
     *
     * <p>Read-then-clear is deliberately atomic: if the app were killed between
     * the two, a "Taken" tap would either be replayed twice or lost. Losing it
     * is the worse failure - the dose would look missed and the user would be
     * nagged about a medicine they already took - so the clear only happens
     * once the caller has the data in hand.
     */
    static String drainActions(Context c) {
        synchronized (LOCK) {
            String json = sp(c).getString(K_ACTIONS, "[]");
            sp(c).edit().putString(K_ACTIONS, "[]").apply();
            return json;
        }
    }

    static int pendingActionCount(Context c) {
        synchronized (LOCK) {
            try {
                return new JSONArray(sp(c).getString(K_ACTIONS, "[]")).length();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /* --------------------------------------------------------------- config */

    static int snoozeMinutes(Context c) {
        int m = sp(c).getInt(K_SNOOZE, 10);
        // Clamp: JavaScript could pass anything, and a 0-minute snooze would
        // re-ring instantly in a loop.
        if (m < 1) return 1;
        if (m > 180) return 180;
        return m;
    }

    static void setSnoozeMinutes(Context c, int m) {
        sp(c).edit().putInt(K_SNOOZE, m).apply();
    }

    /**
     * Notification button labels, pushed down from the app so they follow the
     * user's chosen language (English / Hindi / Kannada). Without this the
     * buttons would be English even for a Kannada-only user - on the one screen
     * they see when the app is closed.
     */
    static void setLabels(Context c, String taken, String snooze, String title, String snoozedUntil) {
        SharedPreferences.Editor e = sp(c).edit();
        if (taken != null && taken.length() > 0) e.putString(K_LBL_TAKEN, taken);
        if (snooze != null && snooze.length() > 0) e.putString(K_LBL_SNOOZE, snooze);
        if (title != null && title.length() > 0) e.putString(K_LBL_TITLE, title);
        if (snoozedUntil != null && snoozedUntil.length() > 0) e.putString(K_LBL_SNOOZED, snoozedUntil);
        e.apply();
    }

    static String labelTaken(Context c, String fallback) {
        return sp(c).getString(K_LBL_TAKEN, fallback);
    }

    static String labelSnooze(Context c, String fallback) {
        return sp(c).getString(K_LBL_SNOOZE, fallback);
    }

    static String labelTitle(Context c, String fallback) {
        return sp(c).getString(K_LBL_TITLE, fallback);
    }

    /** Template for the snooze confirmation, e.g. {@code "Snoozed until %1$s"}.
     *  Pushed down translated so a Kannada-only user is not shown English on the
     *  one screen they see while the app is closed. */
    static String labelSnoozedUntil(Context c, String fallback) {
        return sp(c).getString(K_LBL_SNOOZED, fallback);
    }

    /* --------------------------------------------------------------- resync */

    static void setNeedsResync(Context c, boolean v) {
        sp(c).edit().putBoolean(K_RESYNC, v).apply();
    }

    static boolean needsResync(Context c) {
        return sp(c).getBoolean(K_RESYNC, false);
    }

    /* --------------------------------------------------------- state mirror */

    static void writeMirror(Context c, String json) {
        synchronized (LOCK) {
            sp(c).edit()
                    .putString(K_MIRROR, json == null ? "" : json)
                    .putLong(K_MIRROR_AT, System.currentTimeMillis())
                    .apply();
        }
    }

    static String readMirror(Context c) {
        synchronized (LOCK) {
            return sp(c).getString(K_MIRROR, "");
        }
    }

    static long mirrorWrittenAt(Context c) {
        return sp(c).getLong(K_MIRROR_AT, 0L);
    }
}
