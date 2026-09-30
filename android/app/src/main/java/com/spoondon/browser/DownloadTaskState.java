package com.spoondon.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable snapshot of a DownloadTask. Written to downloads.json by
 * DownloadStore. The runtime task (OkHttp client, threads, cancel flags)
 * lives in DownloadTask, not here.
 */
public final class DownloadTaskState {

    public long id;
    public String url;
    public String fileName;
    public String mime;
    public String userAgent;
    public String referer;
    public String cookies;
    public int state;             // DownloadTask.State ordinal
    public long bytesTotal;       // -1 if unknown (chunked transfer)
    public long bytesDownloaded;
    public String errorMessage;
    public long createdAt;
    public long completedAt;
    public final List<Chunk> chunks = new ArrayList<>();

    /** One contiguous byte range. end is inclusive, or -1 if unknown. */
    public static final class Chunk {
        public long start;
        public long end;
        public long downloaded;
        public boolean done;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("url", url == null ? "" : url);
            o.put("fileName", fileName == null ? "" : fileName);
            o.put("mime", mime == null ? "" : mime);
            o.put("userAgent", userAgent == null ? "" : userAgent);
            o.put("referer", referer == null ? "" : referer);
            o.put("cookies", cookies == null ? "" : cookies);
            o.put("state", state);
            o.put("bytesTotal", bytesTotal);
            o.put("bytesDownloaded", bytesDownloaded);
            o.put("errorMessage", errorMessage == null ? "" : errorMessage);
            o.put("createdAt", createdAt);
            o.put("completedAt", completedAt);

            JSONArray arr = new JSONArray();
            for (Chunk c : chunks) {
                JSONObject co = new JSONObject();
                co.put("start", c.start);
                co.put("end", c.end);
                co.put("downloaded", c.downloaded);
                co.put("done", c.done);
                arr.put(co);
            }
            o.put("chunks", arr);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Nullable
    public static DownloadTaskState fromJson(@NonNull JSONObject o) {
        try {
            DownloadTaskState s = new DownloadTaskState();
            s.id = o.optLong("id");
            s.url = o.optString("url");
            s.fileName = o.optString("fileName");
            s.mime = o.optString("mime");
            s.userAgent = o.optString("userAgent");
            s.referer = o.optString("referer");
            s.cookies = o.optString("cookies");
            s.state = o.optInt("state");
            s.bytesTotal = o.optLong("bytesTotal", -1);
            s.bytesDownloaded = o.optLong("bytesDownloaded");
            s.errorMessage = o.optString("errorMessage");
            s.createdAt = o.optLong("createdAt");
            s.completedAt = o.optLong("completedAt");

            JSONArray arr = o.optJSONArray("chunks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject co = arr.optJSONObject(i);
                    if (co == null) continue;
                    Chunk c = new Chunk();
                    c.start = co.optLong("start");
                    c.end = co.optLong("end");
                    c.downloaded = co.optLong("downloaded");
                    c.done = co.optBoolean("done");
                    s.chunks.add(c);
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
