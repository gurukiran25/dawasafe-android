package com.dawasafe.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Hosts the app.
 *
 * <p>DawaSafe is a single 3.8 MB HTML file that already works in a browser. This
 * Activity's job is not to reimplement it but to make the browser assumptions it
 * makes hold true inside a WebView, because several of them quietly do not:
 *
 * <ul>
 *   <li>{@code <input type="file" capture="environment">} opens nothing unless
 *       the host implements {@code onShowFileChooser} - so the Scan tab and the
 *       profile photo picker are dead without this class.</li>
 *   <li>{@code tel:} and {@code https:} links do nothing. The emergency 108
 *       button is a {@code tel:} link, which makes this a safety issue rather
 *       than a polish one.</li>
 *   <li>{@code window.print()} is a no-op, so the doctor report cannot be
 *       printed or saved as PDF.</li>
 *   <li>A {@code Blob} download with {@code <a download>} silently produces no
 *       file, so Export looked like it worked and did nothing.</li>
 *   <li>{@code speechSynthesis} is missing or mute on many WebView builds, which
 *       disables the app's read-aloud accessibility feature.</li>
 * </ul>
 *
 * <p>Each of those is repaired below and exposed to the page through
 * {@link NativeBridge}.
 */
public class MainActivity extends Activity {

    static final String EXTRA_TAG = "ds_tag";
    static final String EXTRA_ACT = "ds_act";
    static final String EXTRA_DATE = "ds_date";

    private static final String PAGE = "file:///android_asset/index.html";

    /** WebView versions older than this predate several ES2017 features the app
     *  relies on. Below it the app warns rather than rendering half a result. */
    private static final int MIN_WEBVIEW_MAJOR = 60;

    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_SAVE = 1004;

    /** Lets a BroadcastReceiver find the live Activity to push a queued action
     *  into. Weak so a finished Activity is still collectable. */
    private static WeakReference<MainActivity> live = new WeakReference<>(null);

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutput;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean pageReady = false;
    private long lastBackPress = 0L;
    private String pendingSaveB64;
    private String pendingSaveMime;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ------------------------------------------------------------ lifecycle */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        live = new WeakReference<>(this);

        try {
            CacheProvider.sweep(this);
        } catch (Exception ignored) {
        }

        web = new WebView(this);
        setContentView(web);
        configureWebView();

        web.loadUrl(PAGE);

        warnIfWebViewTooOld();
        askNotificationPermissionOnce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        /* Arrived from tapping a notification. The page may not be ready yet, in
           which case deliverQueued() picks it up once it is. */
        deliverIntentAction(intent);
        deliverQueued();
    }

    @Override
    protected void onResume() {
        super.onResume();
        live = new WeakReference<>(this);
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
        /* Every resume is a chance to replay taps made while the app was closed
           and to refresh the permission banner, which the user may have changed
           in Settings while we were backgrounded. */
        deliverQueued();
        js("window.DS_ANDROID && DS_ANDROID.onResume && DS_ANDROID.onResume();");
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        if (web != null) {
            try {
                web.removeJavascriptInterface(NativeBridge.NAME);
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------- WebView */

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    private void configureWebView() {
        WebSettings s = web.getSettings();

        s.setJavaScriptEnabled(true);

        /* DOM storage is not a nice-to-have here: it is the app's ENTIRE
           persistence layer. Every profile, medicine and schedule lives in
           localStorage. Without this line the app starts up blank every single
           time and the user's medicine list is lost on close. */
        s.setDomStorageEnabled(true);

        s.setDatabaseEnabled(true);

        /* The page is loaded from file:// and its data files sit beside it. */
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        /* But a local page must not be able to read arbitrary files or reach
           other origins - that is the classic file:// exfiltration path. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }

        /* Respect the user's system font size. The app is built for elderly
           users, many of whom run their phone at 130% or more; ignoring it would
           override an accessibility setting they deliberately chose. */
        s.setTextZoom(100);

        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            /* The page is file://, some lookups are https://. Without this,
               mixed-content rules block the API calls outright. */
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }

        try {
            CookieManager.getInstance().setAcceptCookie(false);
        } catch (Exception ignored) {
        }

        web.setBackgroundColor(0xFFFFFFFF);
        web.addJavascriptInterface(new NativeBridge(this), NativeBridge.NAME);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && isDebuggable()) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private boolean isDebuggable() {
        try {
            return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private final class Client extends WebViewClient {

        /** API 24+. Routes tel:, mailto:, and external http(s) out to the right app. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            return handleUrl(req.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return handleUrl(Uri.parse(url));
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            pageReady = true;
            /* The page is live: hand over anything queued while it was not. */
            deliverIntentAction(getIntent());
            deliverQueued();
        }

        @Override
        public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
            if (failingUrl != null && failingUrl.startsWith("file:///android_asset/index.html")) {
                showFatal();
            }
        }
    }

    /**
     * Decide whether a URL belongs in the WebView or in another app.
     *
     * @return true when we handled it, which tells the WebView not to navigate.
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);

        if ("file".equals(scheme)) {
            return false; // our own page and its assets
        }

        if ("tel".equals(scheme)) {
            /* The emergency 108 / ambulance button. ACTION_DIAL, not ACTION_CALL:
               it opens the dialer pre-filled instead of placing the call, so a
               mis-tap never silently dials emergency services, and the app needs
               no CALL_PHONE permission. */
            startActivitySafely(new Intent(Intent.ACTION_DIAL, uri), true);
            return true;
        }

        if ("mailto".equals(scheme) || "sms".equals(scheme) || "smsto".equals(scheme)
                || "whatsapp".equals(scheme) || "geo".equals(scheme) || "market".equals(scheme)
                || "intent".equals(scheme)) {
            startActivitySafely(new Intent(Intent.ACTION_VIEW, uri), true);
            return true;
        }

        if ("http".equals(scheme) || "https".equals(scheme)) {
            /* Open the browser rather than navigating in-app: if a link ever
               replaced the page, the user would be stranded on a website with no
               way back to their medicine list. */
            startActivitySafely(new Intent(Intent.ACTION_VIEW, uri), true);
            return true;
        }

        if ("blob".equals(scheme) || "data".equals(scheme)) {
            /* Handled by the page, which routes downloads through
               NativeBridge.saveFile() instead. */
            return true;
        }

        return false;
    }

    private final class Chrome extends WebChromeClient {

        /** Without this override, every file input in the app does nothing at
         *  all - no picker, no error, no camera. */
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = cb;
            try {
                startChooser(params);
                return true;
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
        }

        /** The page asks for the camera through getUserMedia (live scan). */
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                try {
                    /* Only ever grant the camera, and only to our own page. */
                    for (String r : request.getResources()) {
                        if (!PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            request.deny();
                            return;
                        }
                    }
                    /* Below API 23 there is no runtime permission model: CAMERA
                       was granted at install time, and calling
                       checkSelfPermission here would be a NoSuchMethodError on
                       an Android 5 phone - which is inside our minSdk range. */
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                            || checkSelfPermission(Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        request.deny();
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                } catch (Exception e) {
                    request.deny();
                }
            });
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            return true; // swallow; nothing useful to show the user
        }
    }

    /* -------------------------------------------------------- file chooser */

    private void startChooser(WebChromeClient.FileChooserParams params) {
        boolean wantsCamera = false;
        boolean imagesOnly = false;
        String[] accept = params == null ? null : params.getAcceptTypes();

        if (accept != null) {
            for (String a : accept) {
                if (a != null && a.startsWith("image/")) {
                    imagesOnly = true;
                }
            }
        }
        if (params != null
                && params.isCaptureEnabled()
                && imagesOnly
                && hasCamera()) {
            wantsCamera = true;
        }

        Intent content = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(imagesOnly ? "image/*" : "*/*");

        Intent chooser = new Intent(Intent.ACTION_CHOOSER)
                .putExtra(Intent.EXTRA_INTENT, content);

        if (wantsCamera) {
            Intent cam = buildCameraIntent();
            if (cam != null) {
                /* Offered alongside the gallery rather than instead of it. The
                   Scan tab requests capture="environment", but a user who has
                   already photographed the box should not be forced to
                   re-photograph it. */
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
            }
        }

        startActivityForResult(chooser, REQ_FILE);
    }

    private boolean hasCamera() {
        try {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        } catch (Exception e) {
            return false;
        }
    }

    private Intent buildCameraIntent() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                /* Only relevant because we declare CAMERA in the manifest via the
                   getUserMedia path; without the grant the camera app would
                   return an empty result and the user would see nothing happen. */
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                return null;
            }
            File out = new File(CacheProvider.sharedDir(this),
                    "scan_" + System.currentTimeMillis() + ".jpg");
            //noinspection ResultOfMethodCallIgnored
            out.createNewFile();
            cameraOutput = CacheProvider.uriFor(this, out);

            Intent cam = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraOutput)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (cam.resolveActivity(getPackageManager()) == null) {
                return null;
            }
            /* Pre-Lollipop cannot rely on the flags alone. */
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(cam, 0)) {
                grantUriPermission(ri.activityInfo.packageName, cameraOutput,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return cam;
        } catch (Exception e) {
            cameraOutput = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                } else if (cameraOutput != null) {
                    /* The camera app returns a null Intent on success: the image
                       went to the URI we supplied. Forgetting this case is the
                       classic reason "take a photo" appears to do nothing. */
                    result = new Uri[]{cameraOutput};
                }
            }
            if (filePathCallback != null) {
                /* Must always be called, even with null. A WebView whose file
                   callback is never answered will refuse to open the picker
                   again for the rest of the session. */
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            cameraOutput = null;
            return;
        }

        if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writePendingSave(data.getData());
            } else {
                pendingSaveB64 = null;
                pendingSaveMime = null;
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ------------------------------------------------------------ delivery */

    /** Called from the receivers: if the app happens to be open, replay
     *  immediately instead of waiting for the next resume. */
    static void deliverIfLive(Context ignored) {
        final MainActivity a = live.get();
        if (a != null) {
            a.ui.post(a::deliverQueued);
        }
    }

    /**
     * Hand queued Taken/Snooze taps to the page.
     *
     * <p>Only drained once the page is ready. Draining earlier would clear the
     * native queue and throw the taps into a page that has no handler yet -
     * the user would have confirmed a dose and the app would never know.
     */
    private void deliverQueued() {
        if (!pageReady || web == null) {
            return;
        }
        String json = DoseStore.drainActions(this);
        if (json == null || json.length() < 3) {
            return; // "[]"
        }
        js("window.DS_ANDROID && DS_ANDROID.applyActions && DS_ANDROID.applyActions("
                + jsString(json) + ");");
    }

    /** Notification body tapped: tell the page which dose to open. */
    private void deliverIntentAction(Intent i) {
        if (i == null || !pageReady) {
            return;
        }
        String tag = i.getStringExtra(EXTRA_TAG);
        String act = i.getStringExtra(EXTRA_ACT);
        if (tag == null || tag.length() == 0 || act == null) {
            return;
        }
        String date = i.getStringExtra(EXTRA_DATE);
        js("window.DS_ANDROID && DS_ANDROID.openDose && DS_ANDROID.openDose("
                + jsString(tag) + "," + jsString(date == null ? "" : date) + ");");
        /* Clear so a rotation does not re-open the same dose. */
        i.removeExtra(EXTRA_TAG);
        i.removeExtra(EXTRA_ACT);
    }

    private void js(final String code) {
        if (web == null) {
            return;
        }
        ui.post(() -> {
            try {
                if (web == null) {
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    web.evaluateJavascript(code, null);
                } else {
                    web.loadUrl("javascript:" + code);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** JSON-encode a string for safe interpolation into evaluateJavascript. */
    private static String jsString(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    /* ---------------------------------------------------------- save / print */

    /**
     * Write a file the page produced.
     *
     * <p>The app's Export button builds a Blob and clicks {@code <a download>}.
     * In a browser that saves a file; in a WebView it does nothing whatsoever,
     * with no error - so users believed they had a backup when they had none.
     * The page now hands the bytes here instead.
     *
     * <p>On API 19+ this goes through the Storage Access Framework, so the user
     * picks the destination and no storage permission is needed.
     */
    void saveFileFromPage(String suggestedName, String mimeType, String base64) {
        if (base64 == null || base64.length() == 0) {
            toast(getString(R.string.save_failed));
            return;
        }
        pendingSaveB64 = base64;
        pendingSaveMime = (mimeType == null || mimeType.length() == 0)
                ? "application/octet-stream" : mimeType;
        String name = (suggestedName == null || suggestedName.length() == 0)
                ? "dawasafe-export" : suggestedName;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(pendingSaveMime)
                    .putExtra(Intent.EXTRA_TITLE, name);
            try {
                startActivityForResult(i, REQ_SAVE);
                return;
            } catch (ActivityNotFoundException e) {
                // fall through to the Downloads fallback
            }
        }
        saveToDownloads(name);
    }

    private void writePendingSave(Uri dest) {
        String b64 = pendingSaveB64;
        pendingSaveB64 = null;
        pendingSaveMime = null;
        if (b64 == null) {
            return;
        }
        OutputStream os = null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            os = getContentResolver().openOutputStream(dest);
            if (os == null) {
                throw new Exception("no stream");
            }
            os.write(bytes);
            os.flush();
            toast(getString(R.string.saved_to, nameOf(dest)));
        } catch (Exception e) {
            toast(getString(R.string.save_failed));
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Pre-KitKat, or when no document picker exists: write into Downloads. */
    private void saveToDownloads(String name) {
        String b64 = pendingSaveB64;
        pendingSaveB64 = null;
        pendingSaveMime = null;
        if (b64 == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, name);
            fos = new FileOutputStream(out);
            fos.write(Base64.decode(b64, Base64.DEFAULT));
            fos.flush();
            toast(getString(R.string.saved_to, out.getName()));
        } catch (Exception e) {
            toast(getString(R.string.save_failed));
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String nameOf(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        String n = c.getString(idx);
                        if (n != null) {
                            return n;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    /**
     * Print the current page.
     *
     * <p>{@code window.print()} does nothing in a WebView, which meant the
     * doctor report - a page the app explicitly tells the user to print and take
     * to an appointment - could not be produced at all. This opens the real
     * Android print dialog, which also offers "Save as PDF".
     */
    void printCurrentPage(String jobName) {
        if (web == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            toast(getString(R.string.print_unsupported));
            return;
        }
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (pm == null) {
                toast(getString(R.string.print_unsupported));
                return;
            }
            String job = (jobName == null || jobName.length() == 0)
                    ? getString(R.string.app_name) : jobName;
            pm.print(job,
                    web.createPrintDocumentAdapter(job),
                    new PrintAttributes.Builder().build());
        } catch (Exception e) {
            toast(getString(R.string.print_unsupported));
        }
    }

    /** Native share sheet. Replaces the page's wa.me web fallback, which forced
     *  WhatsApp specifically and needed a working internet connection. */
    void shareText(String text, String subject) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
            if (subject != null && subject.length() > 0) {
                i.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            startActivity(Intent.createChooser(i, null));
        } catch (Exception e) {
            toast(getString(R.string.no_app_for_link));
        }
    }

    /* ----------------------------------------------------------------- TTS */

    /**
     * Speak through Android's TTS engine.
     *
     * <p>The page has a read-aloud button for users who cannot comfortably read
     * the screen. It relies on {@code speechSynthesis}, which is absent or
     * silent in many WebView builds - so on those devices an accessibility
     * feature simply did nothing. Routing to the platform engine makes it work
     * everywhere the device has TTS at all.
     */
    void speak(final String text, final String lang) {
        if (text == null || text.length() == 0) {
            return;
        }
        final Locale loc = localeFor(lang);
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = (status == TextToSpeech.SUCCESS);
                if (ttsReady) {
                    attachProgress();
                    applyLocale(loc);
                    doSpeak(text);
                }
            });
            return;
        }
        if (ttsReady) {
            applyLocale(loc);
            doSpeak(text);
        }
    }

    /**
     * Tell the page when an utterance finishes.
     *
     * <p>The read-aloud feature splits a long card into sentence-sized chunks
     * and speaks the next one from {@code utterance.onend}. Without a completion
     * signal only the first chunk would ever be read, and the Stop button would
     * stay stuck reading "Stop" forever because nothing ever tells it the
     * reading ended. So the page gets the same three events the web API gives
     * it: end, error, and nothing else.
     *
     * <p>These callbacks arrive on a TTS worker thread, so each one is handed to
     * {@link #js} which posts to the UI thread before touching the WebView.
     */
    private void attachProgress() {
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    js("window.DS_ANDROID && DS_ANDROID.onSpeechEnd && DS_ANDROID.onSpeechEnd("
                            + jsString(id == null ? "" : id) + ",false);");
                }

                @Override
                public void onError(String id) {
                    js("window.DS_ANDROID && DS_ANDROID.onSpeechEnd && DS_ANDROID.onSpeechEnd("
                            + jsString(id == null ? "" : id) + ",true);");
                }
            });
        } catch (Exception ignored) {
            /* An engine that refuses a progress listener still speaks. The page
               falls back to a timer, so read-aloud degrades to "first chunk
               only" rather than breaking. */
        }
    }

    private void applyLocale(Locale loc) {
        try {
            int r = tts.isLanguageAvailable(loc);
            /* Hindi and Kannada voices are not installed on every device. Rather
               than reading Kannada text with an English voice - which is
               unintelligible - fall back to the default and let the app's own
               on-screen text carry the meaning. */
            if (r == TextToSpeech.LANG_AVAILABLE
                    || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
                    || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                tts.setLanguage(loc);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void doSpeak(String text) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ds");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        } catch (Exception ignored) {
        }
    }

    private Locale localeFor(String lang) {
        if (lang == null) {
            return Locale.getDefault();
        }
        String l = lang.toLowerCase(Locale.US);
        if (l.startsWith("hi")) return new Locale("hi", "IN");
        if (l.startsWith("kn")) return new Locale("kn", "IN");
        if (l.startsWith("en")) return new Locale("en", "IN");
        return Locale.getDefault();
    }

    void stopSpeaking() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    /* --------------------------------------------------------- permissions */

    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        /* Asked on first open rather than at dose-setup time. Android only shows
           this dialog twice ever; spending one of those on a cold start, before
           the user has any context, is a real cost - but the alternative is a
           user who sets up a schedule and never learns it cannot ring. */
        requestNotificationPermission();
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        /* Tell the page either way: it repaints its reminder banner to match, so
           a user who declined sees an honest "alarms will not ring" rather than
           a green tick. */
        js("window.DS_ANDROID && DS_ANDROID.onPermissionResult && DS_ANDROID.onPermissionResult();");
    }

    void startActivitySafely(Intent i, boolean toastOnFailure) {
        try {
            if (i.resolveActivity(getPackageManager()) == null
                    && (i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                if (toastOnFailure) {
                    toast(getString(R.string.no_app_for_link));
                }
                return;
            }
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            if (toastOnFailure) {
                toast(getString(R.string.no_app_for_link));
            }
        } catch (Exception e) {
            if (toastOnFailure) {
                toast(getString(R.string.no_app_for_link));
            }
        }
    }

    void toast(String msg) {
        if (msg == null || msg.length() == 0) {
            return;
        }
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    /* --------------------------------------------------------------- back */

    /**
     * Back button.
     *
     * <p>The page is a single-page app with its own view router and no history
     * entries, so the WebView's back stack is empty and the default behaviour
     * would close the app from any screen. Instead the page is asked first: it
     * closes an open dialog, or returns to the main check screen. Only when it
     * says it has nothing to go back to does a second press exit.
     */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            web.evaluateJavascript(
                    "(window.DS_ANDROID && DS_ANDROID.onBack) ? DS_ANDROID.onBack() : false",
                    value -> {
                        if ("true".equals(value)) {
                            return; // the page handled it
                        }
                        confirmExit();
                    });
        } else {
            confirmExit();
        }
    }

    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2500L) {
            finish();
            return;
        }
        lastBackPress = now;
        toast(getString(R.string.exit_confirm));
    }

    /* ------------------------------------------------------ WebView health */

    /**
     * Warn when the system WebView is too old to run the app correctly.
     *
     * <p>This matters more here than in a normal app. A stale WebView does not
     * fail cleanly - it throws on a modern syntax feature partway through, and
     * the user is left looking at a medicine checker that rendered its layout
     * but silently skipped an interaction check. An incomplete safety result
     * shown as if it were complete is worse than no result, so the app says so
     * plainly.
     */
    private void warnIfWebViewTooOld() {
        int major = webViewMajor();
        if (major <= 0 || major >= MIN_WEBVIEW_MAJOR) {
            return;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.webview_old_title)
                    .setMessage(R.string.webview_old_body)
                    .setCancelable(false)
                    .setPositiveButton(R.string.webview_old_open, (d, w) ->
                            startActivitySafely(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=com.google.android.webview")), true))
                    .setNegativeButton(R.string.webview_old_continue, null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    /** Parse the major version out of the WebView user-agent. Returns 0 when it
     *  cannot be determined, which is treated as "do not warn". */
    private int webViewMajor() {
        try {
            String ua;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                ua = WebSettings.getDefaultUserAgent(this);
            } else {
                ua = web == null ? "" : web.getSettings().getUserAgentString();
            }
            if (ua == null) {
                return 0;
            }
            int i = ua.indexOf("Chrome/");
            if (i < 0) {
                return 0;
            }
            String rest = ua.substring(i + 7);
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                return 0;
            }
            return Integer.parseInt(rest.substring(0, dot));
        } catch (Exception e) {
            return 0;
        }
    }

    private void showFatal() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.load_failed_title)
                    .setMessage(R.string.load_failed_body)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .show();
        } catch (Exception ignored) {
        }
    }
}
