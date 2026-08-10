package com.dawasafe.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;

/**
 * A deliberately tiny stand-in for AndroidX's FileProvider.
 *
 * <h3>Why write this instead of using FileProvider</h3>
 * The camera app needs somewhere to write the photo the Scan tab asked for, and
 * it must be handed a {@code content://} URI: passing {@code file://} throws
 * FileUriExposedException from Android 7 onwards. The usual answer is
 * androidx.core FileProvider, but this project has no dependencies on purpose -
 * a CI build that cannot resolve an artifact is a build the user cannot fix. The
 * alternative was to drop camera capture on Android 7 to 9, which is a large
 * share of the cheap handsets this app is for.
 *
 * <h3>Scope, kept as small as possible</h3>
 * A ContentProvider is instantiated before anything else in the process, and it
 * is the app's widest attack surface, so this one:
 * <ul>
 *   <li>serves exactly one directory - {@code cacheDir/shared} - and nothing else;</li>
 *   <li>resolves through canonical paths, so {@code ../../databases/x} cannot
 *       escape it;</li>
 *   <li>is not exported. Access is only ever via a per-Intent URI grant.</li>
 * </ul>
 * Files here are transient camera output. Nothing durable is stored.
 */
public class CacheProvider extends ContentProvider {

    static final String AUTHORITY = "com.dawasafe.app.files";
    private static final String DIR = "shared";

    /** The one directory this provider will serve. */
    static File sharedDir(Context c) {
        File d = new File(c.getCacheDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    static Uri uriFor(Context c, File f) {
        sharedDir(c);
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(f.getName())
                .build();
    }

    /**
     * Map a URI back to a real file, refusing anything outside the shared dir.
     *
     * @return null when the path escapes the directory, which the callers treat
     *         as "file not found" rather than surfacing a reason.
     */
    private File resolve(Uri uri) throws Exception {
        Context c = getContext();
        if (c == null || uri == null) {
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) {
            return null;
        }
        File dir = sharedDir(c);
        File f = new File(dir, name);
        String base = dir.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(base)) {
            return null; // path traversal attempt
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        /* Must not do real work. This runs before Application.onCreate on a cold
           start, and anything that throws here is a crash before the first frame. */
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null) {
            return "application/octet-stream";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "application/octet-stream";
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    /**
     * Only DISPLAY_NAME and SIZE. That is all a receiving app asks for, and
     * answering more would leak paths.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            if (f == null || !f.exists()) {
                return null;
            }
            String[] cols = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor cur = new MatrixCursor(cols, 1);
            MatrixCursor.RowBuilder row = cur.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    row.add(f.getName());
                } else if (OpenableColumns.SIZE.equals(col)) {
                    row.add(f.length());
                } else {
                    row.add(null);
                }
            }
            return cur;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Opened read-write: the camera app receives this URI in EXTRA_OUTPUT and
     * has to be able to write the captured image into it.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            File f = resolve(uri);
            if (f == null) {
                throw new java.io.FileNotFoundException("not available");
            }
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            }
            int flags = "r".equals(mode)
                    ? ParcelFileDescriptor.MODE_READ_ONLY
                    : ParcelFileDescriptor.MODE_READ_WRITE;
            return ParcelFileDescriptor.open(f, flags);
        } catch (java.io.FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new java.io.FileNotFoundException("not available");
        }
    }

    /* Writes through the ContentResolver are not part of the contract. These
       return inert values rather than throwing, so a misbehaving app cannot
       crash our process by calling them. */

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            File f = resolve(uri);
            if (f != null && f.exists() && f.delete()) {
                return 1;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Delete leftover capture files. Called on app start so a cancelled scan
     *  does not leave a photo of a medicine strip in the cache indefinitely. */
    static void sweep(Context c) {
        try {
            File[] fs = sharedDir(c).listFiles();
            if (fs == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (File f : fs) {
                if (f.isFile() && f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }
}
