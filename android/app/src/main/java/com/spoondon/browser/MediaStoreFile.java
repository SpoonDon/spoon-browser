package com.spoondon.browser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * SeekableFile backed by a MediaStore.Downloads collection row (API 29+).
 *
 * The row is inserted with IS_PENDING=1 so the file is invisible to other
 * apps while it's being written. On successful completion, commit() clears
 * IS_PENDING and the file becomes visible in file managers. On failure,
 * delete() removes the staging row.
 *
 * Writes go through FileChannel.positional writes on the fd returned by
 * ContentResolver.openFileDescriptor(uri, "rw"). No staging copy. This is
 * the same path Chromium uses.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public final class MediaStoreFile implements SeekableFile {

    private static final String TAG = "MediaStoreFile";
    public static final String SUBDIR = "Spoon";

    private final Context appContext;
    private final Uri uri;
    private final ParcelFileDescriptor pfd;
    private final FileChannel channel;
    private volatile long highWater = 0L;
    private volatile boolean closed = false;
    private volatile boolean committed = false;

    private MediaStoreFile(@NonNull Context appContext,
                           @NonNull Uri uri,
                           @NonNull ParcelFileDescriptor pfd,
                           @NonNull FileChannel channel) {
        this.appContext = appContext;
        this.uri = uri;
        this.pfd = pfd;
        this.channel = channel;
    }

    @NonNull
    public Uri getUri() { return uri; }

    /**
     * Insert a MediaStore.Downloads row and open a seekable channel to it.
     * Returns null on any failure — the caller falls back to RealFile.
     */
    @Nullable
    public static MediaStoreFile create(@NonNull Context context,
                                        @NonNull String displayName,
                                        @Nullable String mime) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;

        Context appContext = context.getApplicationContext();
        ContentResolver cr = appContext.getContentResolver();

        String safeMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream" : mime;
        String safeName = DownloadNaming.sanitize(displayName);
        if (safeName.isEmpty()) safeName = "download.bin";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
        values.put(MediaStore.Downloads.MIME_TYPE, safeMime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = null;
        ParcelFileDescriptor pfd = null;
        try {
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.w(TAG, "insert returned null");
                return null;
            }
            pfd = cr.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                Log.w(TAG, "openFileDescriptor returned null");
                cr.delete(uri, null, null);
                return null;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            FileChannel ch = new FileOutputStream(fd).getChannel();
            return new MediaStoreFile(appContext, uri, pfd, ch);
        } catch (Exception e) {
            Log.w(TAG, "create failed", e);
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            if (uri != null) try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // SeekableFile
    // ------------------------------------------------------------------------

    @Override
    public void writeAt(long pos, @NonNull byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("MediaStoreFile closed");
        if (len <= 0) return;
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int written = channel.write(bb, p);
            if (written <= 0) throw new IOException("short write");
            p += written;
        }
        if (p > highWater) highWater = p;
    }

    @Override
    public void setLength(long length) {
        // Cannot extend via FileChannel.truncate() — position writes past
        // EOF extend the file naturally. Track high-water only.
        if (length > highWater) highWater = length;
    }

    @Override
    public long size() throws IOException {
        // Trust the cached high-water mark; pfd.getStatSize() may be stale.
        return highWater;
    }

    @NonNull
    @Override
    public Uri toOpenUri(@NonNull Context ctx) {
        // MediaStore URIs grant read to any receiver via the collection.
        return uri;
    }

    @Nullable
    @Override
    public String toDisplayPath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "/"
                + displayNameFromUri();
    }

    @Override
    public void commit() throws IOException {
        if (committed || closed) return;
        committed = true;
        try {
            channel.force(true);
        } catch (Exception e) {
            Log.w(TAG, "channel.force failed (non-fatal)", e);
        }
        try {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            appContext.getContentResolver().update(uri, done, null, null);
        } catch (Exception e) {
            Log.w(TAG, "clear IS_PENDING failed", e);
            throw new IOException("commit failed", e);
        }
    }

    @Override
    public void delete() {
        try {
            appContext.getContentResolver().delete(uri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "delete failed for " + uri, e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { channel.close(); } catch (Exception ignored) {}
        try { pfd.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------

    @NonNull
    private String displayNameFromUri() {
        try {
            String s = uri.getLastPathSegment();
            return s == null ? "download" : s;
        } catch (Exception e) {
            return "download";
        }
    }
}
