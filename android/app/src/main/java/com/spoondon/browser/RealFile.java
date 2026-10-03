package com.spoondon.browser;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * SeekableFile backed by a real filesystem path.
 *
 * Used on API 24-28 (public Downloads/Spoon) and on API 29+ only if the
 * MediaStore path could not be initialized (rare failure mode).
 */
public final class RealFile implements SeekableFile {

    private static final String TAG = "RealFile";

    private final File file;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private volatile long highWater = 0L;
    private volatile boolean closed = false;

    private RealFile(@NonNull File file,
                     @NonNull RandomAccessFile raf,
                     @NonNull FileChannel channel) {
        this.file = file;
        this.raf = raf;
        this.channel = channel;
    }

    /**
     * Open (or create) the given file. Returns null on I/O failure.
     * Parent directories are created as needed.
     */
    @Nullable
    public static RealFile create(@NonNull File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdirs failed: " + parent);
                return null;
            }
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            FileChannel ch = raf.getChannel();
            return new RealFile(file, raf, ch);
        } catch (Exception e) {
            Log.w(TAG, "create failed for " + file, e);
            return null;
        }
    }

    @NonNull
    public File getFile() { return file; }

    // ------------------------------------------------------------------------
    // SeekableFile
    // ------------------------------------------------------------------------

    @Override
    public void writeAt(long pos, @NonNull byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("RealFile closed");
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
    public void setLength(long length) throws IOException {
        if (closed) return;
        try {
            raf.setLength(length);
            if (length > highWater) highWater = length;
        } catch (IOException e) {
            // Non-fatal: parallel writes extend the file naturally.
            Log.w(TAG, "setLength(" + length + ") failed (non-fatal)", e);
        }
    }

    @Override
    public long size() throws IOException {
        try {
            return raf.length();
        } catch (IOException e) {
            return highWater;
        }
    }

    @Nullable
    @Override
    public Uri toOpenUri(@NonNull Context ctx) {
        try {
            String authority = ctx.getPackageName() + ".fileprovider";
            return FileProvider.getUriForFile(ctx, authority, file);
        } catch (Exception e) {
            Log.w(TAG, "FileProvider URI failed for " + file, e);
            return null;
        }
    }

    @Nullable
    @Override
    public String toDisplayPath() {
        return file.getAbsolutePath();
    }

    @Override
    public void commit() {
        // Filesystem already persisted. Nothing to finalize.
    }

    @Override
    public void delete() {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { channel.close(); } catch (Exception ignored) {}
        try { raf.close(); } catch (Exception ignored) {}
    }
}
