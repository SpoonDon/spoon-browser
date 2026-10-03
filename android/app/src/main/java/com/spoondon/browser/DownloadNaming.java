package com.spoondon.browser;

import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the best possible filename for a download.
 *
 * Priority:
 *   1. RFC 5987 Content-Disposition  (filename*=UTF-8''name.mp4)
 *   2. Simple Content-Disposition     (filename="name.mp4")
 *   3. URL basename                   (/path/name.mp4?query -> name.mp4)
 *   4. MIME type -> extension         (video/mp4 -> .mp4)
 *   5. Extension sniffed from URL     (.mkv anywhere in the path)
 *   6. "download.bin"                 (last resort)
 *
 * sanitize() strips path separators, reserved chars, control chars,
 * leading/trailing dots and whitespace, collapses underscores, and
 * truncates to 180 chars preserving the extension.
 */
public final class DownloadNaming {

    private DownloadNaming() {}

    // ---------- regex ----------

    private static final Pattern RFC5987 = Pattern.compile(
            "filename\\*\\s*=\\s*([^']*)'[^']*'([^;\\r\\n]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SIMPLE = Pattern.compile(
            "filename\\s*=\\s*\"([^\"]+)\"|filename\\s*=\\s*([^;\\r\\n]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXT_IN_URL = Pattern.compile(
            "\\.([A-Za-z0-9]{1,5})(?:$|[?#])");

    // ---------- MIME -> ext overrides ----------

    private static final Map<String, String> MIME_OVERRIDES = new HashMap<>();
    static {
        MIME_OVERRIDES.put("video/mp4", "mp4");
        MIME_OVERRIDES.put("video/x-matroska", "mkv");
        MIME_OVERRIDES.put("video/webm", "webm");
        MIME_OVERRIDES.put("video/quicktime", "mov");
        MIME_OVERRIDES.put("video/x-msvideo", "avi");
        MIME_OVERRIDES.put("video/mpeg", "mpg");
        MIME_OVERRIDES.put("video/3gpp", "3gp");
        MIME_OVERRIDES.put("audio/mpeg", "mp3");
        MIME_OVERRIDES.put("audio/mp4", "m4a");
        MIME_OVERRIDES.put("audio/ogg", "ogg");
        MIME_OVERRIDES.put("audio/flac", "flac");
        MIME_OVERRIDES.put("audio/x-wav", "wav");
        MIME_OVERRIDES.put("audio/webm", "weba");
        MIME_OVERRIDES.put("application/pdf", "pdf");
        MIME_OVERRIDES.put("application/zip", "zip");
        MIME_OVERRIDES.put("application/x-rar-compressed", "rar");
        MIME_OVERRIDES.put("application/x-7z-compressed", "7z");
        MIME_OVERRIDES.put("application/x-tar", "tar");
        MIME_OVERRIDES.put("application/gzip", "gz");
        MIME_OVERRIDES.put("application/x-iso9660-image", "iso");
        MIME_OVERRIDES.put("application/vnd.android.package-archive", "apk");
        MIME_OVERRIDES.put("application/epub+zip", "epub");
        MIME_OVERRIDES.put("application/msword", "doc");
        MIME_OVERRIDES.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        MIME_OVERRIDES.put("application/vnd.ms-excel", "xls");
        MIME_OVERRIDES.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        MIME_OVERRIDES.put("application/vnd.ms-powerpoint", "ppt");
        MIME_OVERRIDES.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        MIME_OVERRIDES.put("image/jpeg", "jpg");
        MIME_OVERRIDES.put("image/png", "png");
        MIME_OVERRIDES.put("image/gif", "gif");
        MIME_OVERRIDES.put("image/webp", "webp");
        MIME_OVERRIDES.put("image/svg+xml", "svg");
        MIME_OVERRIDES.put("image/heic", "heic");
        MIME_OVERRIDES.put("text/plain", "txt");
        MIME_OVERRIDES.put("text/html", "html");
        MIME_OVERRIDES.put("text/css", "css");
        MIME_OVERRIDES.put("application/json", "json");
        MIME_OVERRIDES.put("application/xml", "xml");
        MIME_OVERRIDES.put("text/csv", "csv");
    }

    // ---------- public API ----------

    @NonNull
    public static String resolve(@NonNull String url,
                                 @Nullable String contentDisposition,
                                 @Nullable String mime) {
        String raw = fromRfc5987(contentDisposition);
        if (raw == null || raw.isEmpty()) raw = fromSimple(contentDisposition);
        if (raw == null || raw.isEmpty()) raw = basenameFromUrl(url);

        String name = sanitize(raw);
        String ext = extensionOf(name);

        if (ext == null || ext.isEmpty()) {
            String picked = extensionFromMime(mime);
            if (picked == null) picked = sniffExtensionFromUrl(url);
            if (picked != null && !picked.isEmpty()) {
                if (name.isEmpty() || "download".equalsIgnoreCase(name)) {
                    name = "download." + picked;
                } else {
                    name = name + "." + picked;
                }
            }
        }

        if (name.isEmpty()) name = "download.bin";
        return name;
    }

    /** True when the given name looks like a fallback (no ext or ".bin"). */
    public static boolean isWeakName(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) return true;
        String ext = extensionOf(name.trim());
        return ext == null || ext.isEmpty() || "bin".equalsIgnoreCase(ext);
    }

    @NonNull
    public static String sanitize(@Nullable String name) {
        if (name == null) return "";
        String s = name;
        s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        s = s.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        s = s.replaceAll("_{2,}", "_");
        if (s.length() > 180) {
            String ext = extensionOf(s);
            String suffix = (ext != null && !ext.isEmpty()) ? "." + ext : "";
            s = s.substring(0, 180 - suffix.length()) + suffix;
        }
        return s;
    }

    // ---------- internals ----------

    @Nullable
    private static String fromRfc5987(@Nullable String cd) {
        if (cd == null) return null;
        Matcher m = RFC5987.matcher(cd);
        if (!m.find()) return null;
        try {
            return URLDecoder.decode(m.group(2).trim().replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return m.group(2).trim();
        }
    }

    @Nullable
    private static String fromSimple(@Nullable String cd) {
        if (cd == null) return null;
        Matcher m = SIMPLE.matcher(cd);
        if (!m.find()) return null;
        String v = (m.group(1) != null) ? m.group(1) : m.group(2);
        return v != null ? v.trim() : null;
    }

    @Nullable
    private static String basenameFromUrl(@NonNull String url) {
        try {
            String path = url;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            int slash = path.lastIndexOf('/');
            String base = slash >= 0 ? path.substring(slash + 1) : path;
            return base.isEmpty() ? null : base;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String sniffExtensionFromUrl(@NonNull String url) {
        Matcher m = EXT_IN_URL.matcher(url);
        if (m.find()) {
            String cand = m.group(1).toLowerCase(Locale.ROOT);
            if (cand.length() <= 5 && cand.indexOf('=') < 0) return cand;
        }
        return null;
    }

    @Nullable
    private static String extensionFromMime(@Nullable String mime) {
        if (mime == null || mime.isEmpty()) return null;
        String key = mime.toLowerCase(Locale.ROOT).trim();
        int semi = key.indexOf(';');
        if (semi >= 0) key = key.substring(0, semi).trim();
        String override = MIME_OVERRIDES.get(key);
        if (override != null) return override;
        String fromSystem = MimeTypeMap.getSingleton().getExtensionFromMimeType(key);
        return (fromSystem != null && !fromSystem.isEmpty()) ? fromSystem : null;
    }

    @Nullable
    private static String extensionOf(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1);
    }
}
