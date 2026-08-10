package com.dawasafe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Builds and posts the dose notification.
 *
 * <p>Written against the framework {@link Notification.Builder} rather than
 * {@code NotificationCompat}, because this project ships with zero third-party
 * dependencies (see app/build.gradle). That costs a few API-level branches
 * below and buys a build that cannot fail on dependency resolution.
 */
final class Notifier {

    /** Dose alarms. Deliberately IMPORTANCE_HIGH so it heads-up and makes sound. */
    static final String CHAN_DOSE = "dawasafe_dose_v2";
    /** Quiet app messages. */
    static final String CHAN_INFO = "dawasafe_info_v1";

    private Notifier() {
    }

    static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Create the channels.
     *
     * <p>Note the {@code _v2} suffix on the dose channel id. A channel's
     * importance and sound are frozen at creation - Android ignores every later
     * change so the user stays in control. If a future version needs a louder
     * or differently-configured dose channel, it must be given a NEW id;
     * editing this method alone would silently do nothing on every phone that
     * already ran the old build.
     */
    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return; // pre-Oreo has no channels; importance comes from the builder
        }
        NotificationManager m = nm(c);
        if (m == null) {
            return;
        }

        if (m.getNotificationChannel(CHAN_DOSE) == null) {
            NotificationChannel dose = new NotificationChannel(
                    CHAN_DOSE,
                    c.getString(R.string.chan_dose_name),
                    NotificationManager.IMPORTANCE_HIGH);
            dose.setDescription(c.getString(R.string.chan_dose_desc));
            dose.enableVibration(true);
            dose.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 600});
            dose.enableLights(true);
            dose.setLightColor(Color.rgb(15, 118, 110));

            /* The alarm sound, played with USAGE_ALARM. This matters more than it
               looks: USAGE_ALARM follows the phone's ALARM volume, which almost
               nobody silences, instead of the notification volume, which many
               people keep at zero all day. A dose reminder the user cannot hear
               is the same as no reminder. It also plays through Do Not Disturb
               on most devices, as an alarm clock does. */
            Uri sound = Settings.System.DEFAULT_ALARM_ALERT_URI;
            if (sound == null) {
                sound = Settings.System.DEFAULT_NOTIFICATION_URI;
            }
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            dose.setSound(sound, attrs);

            dose.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            dose.setShowBadge(true);
            /* Let the dose channel through Do Not Disturb. The user can still
               revoke this in Settings; we ask because a missed cardiac or
               diabetes dose is not a "can wait until morning" notification. */
            dose.setBypassDnd(true);
            m.createNotificationChannel(dose);
        }

        if (m.getNotificationChannel(CHAN_INFO) == null) {
            NotificationChannel info = new NotificationChannel(
                    CHAN_INFO,
                    c.getString(R.string.chan_info_name),
                    NotificationManager.IMPORTANCE_LOW);
            info.setDescription(c.getString(R.string.chan_info_desc));
            info.setShowBadge(false);
            m.createNotificationChannel(info);
        }
    }

    /** PendingIntent flags. FLAG_IMMUTABLE is mandatory from API 31 and safe
     *  everywhere from API 23 - without it, targetSdk 34 throws at runtime. */
    static int piFlags(int extra) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | extra;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    /**
     * Post the notification for one due dose.
     *
     * @param dose the stored dose object: {@code id, tag, date, title, body}.
     */
    @SuppressWarnings("deprecation")
    static void showDose(Context c, JSONObject dose) {
        NotificationManager m = nm(c);
        if (m == null || dose == null) {
            return;
        }
        ensureChannels(c);

        final int id = dose.optInt("id", 0);
        final String tag = dose.optString("tag", "");
        final String date = dose.optString("date", "");

        String title = dose.optString("title", "");
        if (title.length() == 0) {
            title = DoseStore.labelTitle(c, c.getString(R.string.notif_dose_title));
        }
        String body = dose.optString("body", "");

        /* Tapping the notification body: open the app on the alarm screen. */
        Intent open = new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra(MainActivity.EXTRA_TAG, tag)
                .putExtra(MainActivity.EXTRA_ACT, "open")
                .putExtra(MainActivity.EXTRA_DATE, date)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(c, id, open, piFlags(0));

        /* Taken / Snooze go to a receiver, not an Activity, so the user does not
           have to wait for a 3.8 MB page to load just to say "yes I took it". */
        PendingIntent piTaken = PendingIntent.getBroadcast(
                c, id * 3 + 1,
                new Intent(c, ActionReceiver.class)
                        .setAction(ActionReceiver.ACTION_TAKEN)
                        .setData(Uri.parse("dawasafe://dose/" + id + "/taken"))
                        .putExtra(ActionReceiver.EXTRA_TAG, tag)
                        .putExtra(ActionReceiver.EXTRA_DATE, date)
                        .putExtra(ActionReceiver.EXTRA_ID, id),
                piFlags(0));

        PendingIntent piSnooze = PendingIntent.getBroadcast(
                c, id * 3 + 2,
                new Intent(c, ActionReceiver.class)
                        .setAction(ActionReceiver.ACTION_SNOOZE)
                        .setData(Uri.parse("dawasafe://dose/" + id + "/snooze"))
                        .putExtra(ActionReceiver.EXTRA_TAG, tag)
                        .putExtra(ActionReceiver.EXTRA_DATE, date)
                        .putExtra(ActionReceiver.EXTRA_ID, id),
                piFlags(0));

        /* setData() with a unique URI on each Intent above is not decoration.
           PendingIntent equality ignores extras entirely - two Intents for the
           same class and action are "equal" even with different tags - so
           without a distinguishing URI, the Taken button for the 8pm dose would
           silently overwrite the one for the 8am dose. */

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHAN_DOSE);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_MAX);
            b.setSound(Settings.System.DEFAULT_ALARM_ALERT_URI);
            b.setVibrate(new long[]{0, 400, 200, 400, 200, 600});
        }

        b.setSmallIcon(R.drawable.ic_stat_dawasafe)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(piOpen)
                .setAutoCancel(false)
                .setOngoing(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setColor(Color.rgb(15, 118, 110));
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            /* CATEGORY_ALARM, not CATEGORY_REMINDER: it raises the ranking and,
               with the channel's DND bypass, is what lets a dose surface on a
               silenced phone. */
            b.setCategory(Notification.CATEGORY_ALARM);
        }

        /* requireInteraction equivalent: the notification stays until the user
           actually answers it. An elderly user who glances at the phone twenty
           minutes later must still find it there. */
        b.setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.addAction(new Notification.Action.Builder(
                    null, DoseStore.labelTaken(c, c.getString(R.string.notif_action_taken)), piTaken).build());
            b.addAction(new Notification.Action.Builder(
                    null, DoseStore.labelSnooze(c, c.getString(R.string.notif_action_snooze)), piSnooze).build());
        } else {
            b.addAction(0, DoseStore.labelTaken(c, c.getString(R.string.notif_action_taken)), piTaken);
            b.addAction(0, DoseStore.labelSnooze(c, c.getString(R.string.notif_action_snooze)), piSnooze);
        }

        /* Full-screen intent: on a locked phone this is what actually wakes the
           screen with the big Taken / Snooze buttons instead of a strip the user
           will never notice. Android downgrades it to a heads-up banner when the
           phone is unlocked and in use, which is the behaviour we want there. */
        Intent full = new Intent(c, AlarmActivity.class)
                .putExtra(MainActivity.EXTRA_TAG, tag)
                .putExtra(MainActivity.EXTRA_DATE, date)
                .putExtra(AlarmActivity.EXTRA_TITLE, title)
                .putExtra(AlarmActivity.EXTRA_BODY, body)
                .putExtra(ActionReceiver.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piFull = PendingIntent.getActivity(c, id * 3 + 3, full, piFlags(0));
        b.setFullScreenIntent(piFull, true);

        try {
            m.notify(id, b.build());
        } catch (SecurityException e) {
            /* POST_NOTIFICATIONS was refused on Android 13+. Nothing to do here;
               MainActivity re-asks on next open and the app's own banner already
               tells the user alarms will not ring. Crashing would be worse. */
        }
    }

    static void cancel(Context c, int id) {
        NotificationManager m = nm(c);
        if (m != null) {
            try {
                m.cancel(id);
            } catch (Exception ignored) {
            }
        }
    }
}
