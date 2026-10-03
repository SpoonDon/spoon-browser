package com.spoondon.browser;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * Platform-agnostic seekable byte sink for a download target.
 *
 * Two implementations exist:
 *   RealFile       — RandomAccessFile + FileChannel, app-private or public
 *                    Downloads/Spoon on API 24-28
 *   MediaStoreFile — MediaStore.Downloads + FileChannel via ParcelFileDescriptor,
 *                    public Download/Spoon on API 29+
 *
 * Writes are POSITIONED — writeAt() does not advance any internal cursor
 * and is safe for concurrent calls from multiple chunk threads (guaranteed
 * by FileChannel.write(ByteBuffer, long)).
 *
 * Lifecycle:
 *   create(...)  -> instance
 *   writeAt(...) x N
 *   commit()     -> finalize (MediaStore: clear IS_PENDING; RealFile: no-op)
 *   close()      -> release file handles
 *
 * On failure/cancel, callers MUST call delete() before close() so any
 * MediaStore staging row is rolled back.
 */
public interface SeekableFile {

    /** Positioned write. Does not advance any shared cursor. */
    void writeAt(long pos, @NonNull byte[] buf, int off, int len) throws IOException;

    /**
     * Best-effort preallocation. MediaStore-backed files cannot be extended
     * via FileChannel.truncate(), so this is a no-op for them — parallel
     * writes past EOF naturally extend the file.
     */
    void setLength(long length) throws IOException;

    /** Current logical size in bytes (high-water mark of written data). */
    long size() throws IOException;

    /**
     * Content URI suitable for Intent.ACTION_VIEW / ACTION_SEND.
     * MediaStore: the collection row URI.
     * RealFile: FileProvider-scoped URI.
     * Returns null if the URI cannot be constructed.
     */
    @Nullable Uri toOpenUri(@NonNull Context ctx);

    /**
     * Human-readable path for the details dialog.
     * MediaStore: "Download/Spoon/<name>"
     * RealFile:   absolute path on the private fs
     */
    @Nullable String toDisplayPath();

    /** Finalize a successful transfer. Idempotent. */
    void commit() throws IOException;

    /** Remove the target. Called on cancel/failure/clear. Idempotent. */
    void delete();

    /** Release handles. Does NOT delete; pair with delete() on failure. */
    void close();
}
