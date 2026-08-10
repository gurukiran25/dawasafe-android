package com.dawasafe.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * The only object the page can see. Injected as {@code window.DawaSafeNative}.
 *
 * <h3>Every method here runs on the wrong thread</h3>
 * WebView dispatches {@code @JavascriptInterface} calls on a private binder
 * thread called "JavaBridge", never on the main thread. Touching a View, showing
 * a dialog, or calling {@code startActivity} from here is a race at best and a
 * crash at worst. So anything with a UI consequence is posted to the Activity,
 * and everything else is restricted to SharedPreferences and AlarmManager, both
 * of which are safe off the main thread.
 *
 * <h3>Why the surface is this narrow</h3>
 * A bridge object is the one hole in the WebView sandbox: any script that runs in
 * the page can call every public method on it. The page is a local asset, so the
 * realistic risk is not a hostile site but a mistake - so no method here takes a
 * file path, an arbitrary URL to load, or a shell string. The most dangerous
 * thing a caller can do is schedule a badly-timed alarm.
 *
 * <h3>What it must never do</h3>
 * It never writes a dose record. Marking a dose taken has to go through the
 * app's own function, which also updates adherence history and the pill count.
 * Native only queues the user's tap and lets the app apply it.
 */
public class NativeBridge {

    static final String NAME = "DawaSafeNative";
    /** Bumped when the JS contract changes, so the page can refuse to talk to an
     *  older APK instead of failing in confusing ways. */
    static final int BRIDGE_VERSION = 1;

    private final Context app;
    private final WeakReference<MainActivity> ref;

    NativeBridge(MainActivity activity) {
        this.app = activity.getApplicationContext();
        this.ref = new WeakReference<>(activity);
    }

    private MainActivity act() {
        return ref.get();
    }

    /* ------------------------------------------------------------ identity */

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    @JavascriptInterface
    public int sdkInt() {
        return Build.VERSION.SDK_INT;
    }

    @JavascriptInterface
    public int bridgeVersion() {
        return BRIDGE_VERSION;
    }

    @JavascriptInterface
    public String appVersion() {
        try {
            return app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String deviceLabel() {
        return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
    }

    /* ---------------------------------------------------------- permission */

    /**
     * Whether dose alarms will fire at the exact minute requested.
     *
     * <p>The app shows a different banner depending on this. It is checked live
     * on every call rather than cached, because the user can revoke exact-alarm
     * permission in Settings while the app is still running.
     */
    @JavascriptInterface
    public boolean canScheduleExact() {
        return AlarmScheduler.isExact(app);
    }

    @JavascriptInterface
    public boolean notificationsEnabled() {
        try {
            NotificationManager m = Notifier.nm(app);
            if (m == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !m.areNotificationsEnabled()) {
                return false;
            }
            /* An app-level "on" is not enough: the user can silence just the dose
               channel and leave the rest on, and then no dose ever makes a
               sound. Report that as "off", because from the user's point of view
               it is. */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = m.getNotificationChannel(Notifier.CHAN_DOSE);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ask for POST_NOTIFICATIONS (API 33+). No-op below that. */
    @JavascriptInterface
    public void requestNotificationPermission() {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(a::requestNotificationPermission);
        }
    }

    /** Open the system page where exact alarms are granted. */
    @JavascriptInterface
    public void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            openAppSettings();
            return;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:" + app.getPackageName()));
        launch(i, true);
    }

    /** Open the app's notification settings, so the user can re-enable a
     *  channel they silenced. */
    @JavascriptInterface
    public void openNotificationSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.getPackageName());
        } else {
            i = appSettingsIntent();
        }
        launch(i, true);
    }

    /**
     * Open battery-optimisation settings.
     *
     * <p>This is the single biggest cause of missed reminders on Indian handsets:
     * Xiaomi, Oppo, Vivo and Realme ship aggressive "battery savers" that kill
     * background apps and drop their alarms, and no amount of correct
     * AlarmManager use survives it. The app can only point the user at the
     * setting and explain it.
     *
     * <p>ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is not used: it is a
     * Play-policy-restricted intent, and on many OEM builds the real control is
     * a vendor screen this does not reach anyway. The generic list is honest
     * about being a starting point.
     */
    @JavascriptInterface
    public void openBatterySettings() {
        Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (!launch(i, false)) {
            launch(appSettingsIntent(), true);
        }
    }

    @JavascriptInterface
    public void openAppSettings() {
        launch(appSettingsIntent(), true);
    }

    private Intent appSettingsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + app.getPackageName()));
    }

    /**
     * Start an Activity from the bridge thread.
     *
     * @param toast when true, tell the user if nothing on the device can handle
     *              it. Silence would look like a dead button.
     * @return true if something was started.
     */
    private boolean launch(Intent i, boolean toast) {
        final MainActivity a = act();
        try {
            if (a != null) {
                a.runOnUiThread(() -> a.startActivitySafely(i, toast));
                return true;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ----------------------------------------------------------- scheduling */

    /**
     * Replace the entire dose schedule.
     *
     * @param json array of {@code {id, tag, date, at, title, body}}. The
     *             {@code id} and {@code tag} are the app's own - native reuses
     *             them rather than deriving its own, so the two sides can never
     *             disagree about which alarm belongs to which dose.
     * @return a JSON summary: {@code {armed, future, nextAt, exact}}, so the
     *         caller can render an accurate banner without a second round trip.
     */
    @JavascriptInterface
    public String schedule(String json) {
        JSONObject out = new JSONObject();
        try {
            JSONArray incoming = new JSONArray(json == null ? "[]" : json);
            JSONArray clean = new JSONArray();

            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                long at = o.optLong("at", 0L);
                int id = o.optInt("id", 0);
                /* Reject anything unusable here rather than storing it and
                   discovering the problem at 3am. An id of 0 collides with "no
                   extra present" in the receiver, and at=0 would fire instantly. */
                if (at <= 0L || id == 0) {
                    continue;
                }
                JSONObject d = new JSONObject();
                d.put("id", id);
                d.put("at", at);
                d.put("tag", o.optString("tag", ""));
                d.put("date", o.optString("date", ""));
                d.put("title", o.optString("title", ""));
                d.put("body", o.optString("body", ""));
                clean.put(d);
            }

            int armed = AlarmScheduler.replaceAll(app, clean);
            /* The schedule has just been rebuilt from the app's live dose times,
               so whatever made it stale is now resolved. */
            DoseStore.setNeedsResync(app, false);

            out.put("ok", true);
            out.put("armed", armed);
            out.put("future", AlarmScheduler.futureCount(app));
            out.put("nextAt", AlarmScheduler.nextAt(app));
            out.put("exact", AlarmScheduler.isExact(app));
            out.put("notifications", notificationsEnabled());
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    /** Current native view of the schedule, for the app's status banner. */
    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("bridge", BRIDGE_VERSION);
            o.put("armed", DoseStore.armedIds(app).length());
            o.put("future", AlarmScheduler.futureCount(app));
            o.put("nextAt", AlarmScheduler.nextAt(app));
            o.put("exact", AlarmScheduler.isExact(app));
            o.put("notifications", notificationsEnabled());
            o.put("pending", DoseStore.pendingActionCount(app));
            o.put("needsResync", DoseStore.needsResync(app));
            o.put("snooze", DoseStore.snoozeMinutes(app));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** The stored dose list, so the app can show what native will actually ring. */
    @JavascriptInterface
    public String getPending() {
        return DoseStore.doses(app).toString();
    }

    @JavascriptInterface
    public void cancel(String idsJson) {
        try {
            AlarmScheduler.cancelIds(app, new JSONArray(idsJson == null ? "[]" : idsJson));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void cancelAll() {
        try {
            AlarmScheduler.cancelAll(app);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void setSnoozeMinutes(int m) {
        DoseStore.setSnoozeMinutes(app, m);
    }

    /** Push translated notification button labels down to native. Without this a
     *  Kannada-only user gets English buttons on the one screen they see while
     *  the app is closed. */
    @JavascriptInterface
    public void setLabels(String taken, String snooze, String title, String snoozedUntil) {
        DoseStore.setLabels(app, taken, snooze, title, snoozedUntil);
    }

    /* -------------------------------------------------------------- actions */

    /**
     * Hand over Taken/Snooze taps that happened while the app was closed, and
     * clear the queue in the same step.
     *
     * <p>Read-and-clear is atomic inside {@link DoseStore}. The caller must
     * therefore apply what it receives: if it throws the array away, those taps
     * are gone, and the user would be asked again about a dose they already
     * confirmed.
     */
    @JavascriptInterface
    public String drainActions() {
        return DoseStore.drainActions(app);
    }

    /* --------------------------------------------------------------- mirror */

    /**
     * Keep a native copy of the app's saved state.
     *
     * <p>The app stores everything in the WebView's DOM storage. That is normally
     * durable, but it is not owned by us: a "phone cleaner" app, a WebView data
     * reset, or the user clearing app storage wipes it, and the medicine list goes
     * with it. This mirror is the last line of defence, and it is why the app can
     * offer to restore instead of starting empty.
     *
     * <p>It is a safety net, never the source of truth: the app reads it only
     * when DOM storage comes back empty.
     */
    @JavascriptInterface
    public void mirrorState(String json) {
        if (json == null || json.length() == 0) {
            return; // never let an empty write destroy a good mirror
        }
        DoseStore.writeMirror(app, json);
    }

    @JavascriptInterface
    public String readMirror() {
        return DoseStore.readMirror(app);
    }

    @JavascriptInterface
    public long mirrorAt() {
        return DoseStore.mirrorWrittenAt(app);
    }

    /* ------------------------------------------------------- host UI bridge */

    /**
     * Save a file the page produced - the JSON backup, or the doctor report.
     *
     * <p>This exists because {@code <a download>} and {@code Blob} URLs do
     * nothing in a WebView: the app's Export button looked like it worked and
     * silently produced no file. Base64 in, real file out, through the system
     * picker so the user chooses where it lands.
     */
    @JavascriptInterface
    public void saveFile(String suggestedName, String mimeType, String base64) {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(() -> a.saveFileFromPage(suggestedName, mimeType, base64));
        }
    }

    /** Print or share the doctor report. {@code window.print()} is a no-op in a
     *  WebView, so the app routes here and gets the real Android print dialog,
     *  which can also "print" to PDF. */
    @JavascriptInterface
    public void printPage(String jobName) {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(() -> a.printCurrentPage(jobName));
        }
    }

    /** Native share sheet, replacing the wa.me web fallback. */
    @JavascriptInterface
    public void share(String text, String subject) {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(() -> a.shareText(text, subject));
        }
    }

    @JavascriptInterface
    public void toast(String msg) {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(() -> a.toast(msg));
        }
    }

    /** Speak through Android TTS. The WebView's speechSynthesis is missing or
     *  broken on many builds, and the app's read-aloud is an accessibility
     *  feature for users who cannot read the screen. */
    @JavascriptInterface
    public void speak(String text, String lang) {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(() -> a.speak(text, lang));
        }
    }

    @JavascriptInterface
    public void stopSpeaking() {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(a::stopSpeaking);
        }
    }

    /** Close the app. Used by the page's own exit confirmation. */
    @JavascriptInterface
    public void exitApp() {
        final MainActivity a = act();
        if (a != null) {
            a.runOnUiThread(a::finish);
        }
    }
}
