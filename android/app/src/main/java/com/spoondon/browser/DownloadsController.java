package com.spoondon.browser;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public facade over {@link DownloadEngine}. Owns the downloads dialog.
 *
 * 2026-10-03 refactor:
 *   - enqueue() now takes the Content-Disposition header and resolves the
 *     filename through {@link DownloadNaming} (CD -> URL basename -> MIME).
 *     The old caller-supplied name is used only as a weak fallback.
 *   - open()/share() use {@link SeekableFile#toOpenUri(Context)} — works
 *     for both MediaStore URIs (API 29+) and FileProvider URIs (legacy).
 *   - The overflow menu gained a "Details" entry for every terminal state;
 *     it opens a read-only summary sheet.
 *   - The item context menu runs through SpoonDialog for visual consistency
 *     with the rest of the app.
 *
 * Status values on DownloadItem remain DownloadManager.STATUS_* constants
 * so any caller that switches on item.status keeps compiling and working.
 */
public final class DownloadsController {

    private final MainActivity activity;
    private final DownloadEngine engine;

    private AlertDialog activeDialog;
    private DownloadAdapter activeAdapter;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    public DownloadsController(@NonNull MainActivity activity) {
        this.activity = activity;
        this.engine = DownloadService.getEngine(activity);
    }

    // ------------------------------------------------------------------------
    // Lifecycle (kept for API compatibility)
    // ------------------------------------------------------------------------

    public void register() { }

    public void unregister() { }

    // ------------------------------------------------------------------------
    // Enqueue
    // ------------------------------------------------------------------------

    /**
     * Start a download. Returns the task id, or -1 on failure. Never throws.
     *
     * @param contentDisposition the HTTP Content-Disposition header, or null.
     *                           Used by DownloadNaming to extract the filename.
     * @param callerFileName     hint from the caller (URLUtil.guessFileName or
     *                           similar). Only used if DownloadNaming falls
     *                           back to "download.bin".
     */
    public long enqueue(@NonNull String url,
                        @Nullable String userAgent,
                        @Nullable String mime,
                        @Nullable String contentDisposition,
                        @Nullable String callerFileName,
                        @Nullable String referer) {
        try {
            // Resolve best name: Content-Disposition > URL basename > MIME ext.
            String resolved = DownloadNaming.resolve(url, contentDisposition, mime);

            // If DownloadNaming couldn't find anything better than ".bin",
            // fall back to the caller's hint (which may have come from a
            // page-supplied filename attribute).
            if (DownloadNaming.isWeakName(resolved)
                    && callerFileName != null
                    && !DownloadNaming.isWeakName(callerFileName)) {
                resolved = DownloadNaming.sanitize(callerFileName);
            }

            String safeName = DownloadNaming.sanitize(resolved);
            if (safeName.isEmpty()) safeName = "download.bin";

            String cookies = null;
            try {
                cookies = android.webkit.CookieManager.getInstance().getCookie(url);
            } catch (Exception ignored) {}

            DownloadSpec spec = new DownloadSpec(
                    url, safeName,
                    (mime == null || mime.isEmpty()) ? null : mime,
                    (userAgent == null || userAgent.isEmpty()) ? null : userAgent,
                    (referer == null || referer.isEmpty()) ? null : referer,
                    (cookies == null || cookies.isEmpty()) ? null : cookies);

            long id = engine.enqueue(spec);
            if (id < 0) {
                Toast.makeText(activity, "Could not start download",
                        Toast.LENGTH_SHORT).show();
                return -1;
            }

            DownloadService.ensureRunning(activity);
            activity.ensureNotificationPermission();

            Toast.makeText(activity, "Download started: " + safeName,
                    Toast.LENGTH_SHORT).show();
            return id;

        } catch (Exception e) {
            Toast.makeText(activity,
                    "Could not start download: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return -1;
        }
    }

    // ------------------------------------------------------------------------
    // Query — maps engine state to the legacy DownloadManager-shaped item
    // ------------------------------------------------------------------------

    @NonNull
    public List<DownloadItem> queryAll() {
        List<DownloadItem> out = new ArrayList<>();
        for (DownloadTask t : engine.getAll()) {
            DownloadItem item = new DownloadItem();
            item.id = t.getId();
            item.title = t.getSpec().fileName;
            item.status = mapStatus(t.getState());
            item.bytesTotal = t.getBytesTotal();
            item.bytesDownloaded = t.getBytesDownloaded();
            item.reason = mapReason(t.getState());
            item.mime = t.getSpec().mime;
            out.add(item);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    private static int mapStatus(@NonNull DownloadTask.State s) {
        switch (s) {
            case QUEUED:     return DownloadManager.STATUS_PENDING;
            case RUNNING:    return DownloadManager.STATUS_RUNNING;
            case PAUSED:     return DownloadManager.STATUS_PAUSED;
            case COMPLETED:  return DownloadManager.STATUS_SUCCESSFUL;
            case FAILED:
            case CANCELLED:
            default:         return DownloadManager.STATUS_FAILED;
        }
    }

    private static int mapReason(@NonNull DownloadTask.State s) {
        return DownloadManager.ERROR_UNKNOWN;
    }

    @Nullable
    public DownloadItem findById(long id) {
        for (DownloadItem it : queryAll()) {
            if (it.id == id) return it;
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Actions — open / share / remove / pause / resume
    // ------------------------------------------------------------------------

    public void open(long id) {
        DownloadTask t = engine.getTask(id);
        if (t == null) {
            Toast.makeText(activity, "Download not found", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.getState() != DownloadTask.State.COMPLETED) {
            Toast.makeText(activity, "File not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = t.getTarget().toOpenUri(activity);
        if (uri == null) {
            Toast.makeText(activity, "File URI unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String mime = (t.getSpec().mime != null && !t.getSpec().mime.isEmpty())
                ? t.getSpec().mime : "*/*";
        try {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No app to open this file", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Could not open file", Toast.LENGTH_SHORT).show();
        }
    }

    public void share(long id) {
        DownloadTask t = engine.getTask(id);
        if (t == null) {
            Toast.makeText(activity, "Download not found", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = t.getTarget().toOpenUri(activity);
        if (uri == null) {
            Toast.makeText(activity, "File URI unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String mime = (t.getSpec().mime != null && !t.getSpec().mime.isEmpty())
                ? t.getSpec().mime : "*/*";
        try {
            Intent i = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(i, "Share download"));
        } catch (Exception e) {
            Toast.makeText(activity, "No app to share with", Toast.LENGTH_SHORT).show();
        }
    }

    public void remove(long id) {
        engine.remove(id);
        if (activeAdapter != null) activeAdapter.setItems(queryAll());
    }

    public void pause(long id) {
        engine.pause(id);
        if (activeAdapter != null) activeAdapter.setItems(queryAll());
    }

    public void resume(long id) {
        engine.resume(id);
        DownloadService.ensureRunning(activity);
        if (activeAdapter != null) activeAdapter.setItems(queryAll());
    }

    // ------------------------------------------------------------------------
    // Dialog
    // ------------------------------------------------------------------------

    public void showDownloadsDialog() {
        DownloadService.ensureRunning(activity);
        List<DownloadItem> items = queryAll();

        if (items.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Downloads")
                    .setMessage("No downloads yet.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        activeAdapter = new DownloadAdapter(activity);
        activeAdapter.setItems(items);

        ListView listView = new ListView(activity);
        listView.setAdapter(activeAdapter);

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            DownloadItem item = activeAdapter.getItem(pos);
            if (item != null) onItemTapped(item);
        });

        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            DownloadItem item = activeAdapter.getItem(pos);
            if (item != null) showItemOptions(item);
            return true;
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloads")
                .setView(listView)
                .setPositiveButton("Clear finished", (d, w) -> clearFinished())
                .setNegativeButton("Close", null)
                .create();

        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            activeDialog = null;
            activeAdapter = null;
            stopPolling();
        });
        dialog.show();

        startPolling();
    }

    private void onItemTapped(@NonNull DownloadItem item) {
        DownloadTask t = engine.getTask(item.id);
        if (t == null) return;
        switch (t.getState()) {
            case COMPLETED:
                open(item.id);
                break;
            case RUNNING:
            case QUEUED:
                pause(item.id);
                break;
            case PAUSED:
                resume(item.id);
                break;
            case FAILED:
            case CANCELLED:
                showItemOptions(item);
                break;
        }
    }

    private void clearFinished() {
        List<DownloadTask> all = engine.getAll();
        for (DownloadTask t : all) {
            DownloadTask.State s = t.getState();
            if (s == DownloadTask.State.COMPLETED
                    || s == DownloadTask.State.FAILED
                    || s == DownloadTask.State.CANCELLED) {
                engine.remove(t.getId());
            }
        }
        if (activeAdapter != null) activeAdapter.setItems(queryAll());
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
    }

    private void startPolling() {
        stopPolling();
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeDialog != null && activeDialog.isShowing() && activeAdapter != null) {
                    activeAdapter.setItems(queryAll());
                    pollHandler.postDelayed(this, 1000);
                }
            }
        };
        pollHandler.postDelayed(pollRunnable, 1000);
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    // ------------------------------------------------------------------------
    // Item context menu — now routed through SpoonDialog for visual parity
    // ------------------------------------------------------------------------

    private void showItemOptions(@NonNull DownloadItem item) {
        DownloadTask t = engine.getTask(item.id);
        if (t == null) return;

        List<SpoonDialog.Item> options = new ArrayList<>();
        DownloadTask.State s = t.getState();
        if (s == DownloadTask.State.COMPLETED) {
            options.add(new SpoonDialog.Item("Open", null, 0, false, false));
            options.add(new SpoonDialog.Item("Share", null, 0, false, false));
        } else if (s == DownloadTask.State.RUNNING || s == DownloadTask.State.QUEUED) {
            options.add(new SpoonDialog.Item("Pause", null, 0, false, false));
        } else if (s == DownloadTask.State.PAUSED) {
            options.add(new SpoonDialog.Item("Resume", null, 0, false, false));
        }
        // Details is available for any terminal state.
        if (s == DownloadTask.State.COMPLETED
                || s == DownloadTask.State.FAILED
                || s == DownloadTask.State.CANCELLED) {
            options.add(new SpoonDialog.Item("Details", null, 0, false, false));
        }
        options.add(new SpoonDialog.Item("Remove", null, 0, false, true));

        SpoonDialog.list(activity, t.getSpec().fileName, options, which -> {
            String label = options.get(which).title;
            switch (label) {
                case "Open":    open(item.id); break;
                case "Share":   share(item.id); break;
                case "Pause":   pause(item.id); break;
                case "Resume":  resume(item.id); break;
                case "Remove":  remove(item.id); break;
                case "Details": showDetails(item); break;
            }
        });
    }

    /**
     * Read-only summary of everything we know about a download.
     * Uses SpoonDialog.message so it matches the rest of the app's chrome.
     */
    private void showDetails(@NonNull DownloadItem item) {
        DownloadTask t = engine.getTask(item.id);
        if (t == null) return;

        StringBuilder sb = new StringBuilder();

        sb.append("Name:\n").append(t.getSpec().fileName).append("\n\n");

        String stateLabel;
        switch (t.getState()) {
            case COMPLETED: stateLabel = "Completed"; break;
            case RUNNING:   stateLabel = "Downloading"; break;
            case PAUSED:    stateLabel = "Paused"; break;
            case QUEUED:    stateLabel = "Queued"; break;
            case FAILED:    stateLabel = "Failed"; break;
            case CANCELLED: stateLabel = "Cancelled"; break;
            default:        stateLabel = "Unknown";
        }
        sb.append("Status: ").append(stateLabel).append("\n\n");

        long total = t.getBytesTotal();
        long done = t.getBytesDownloaded();
        if (total > 0) {
            sb.append("Size: ").append(humanSize(done))
              .append(" / ").append(humanSize(total)).append("\n\n");
        } else if (done > 0) {
            sb.append("Size: ").append(humanSize(done)).append("\n\n");
        }

        String mime = t.getSpec().mime;
        if (mime != null && !mime.isEmpty()) {
            sb.append("Type: ").append(mime).append("\n\n");
        }

        sb.append("Source:\n").append(t.getSpec().url).append("\n\n");

        String path = t.getTarget().toDisplayPath();
        if (path != null && !path.isEmpty()) {
            sb.append("Saved to:\n").append(path);
        }

        String err = t.getErrorMessage();
        if (err != null && !err.isEmpty()) {
            sb.append("\n\nError:\n").append(err);
        }

        SpoonDialog.message(activity,
                "Download details",
                sb.toString(),
                "OK",
                null,
                () -> { });
    }

    // ------------------------------------------------------------------------
    // Item + Adapter (shape unchanged from the DownloadManager version)
    // ------------------------------------------------------------------------

    public static class DownloadItem {
        public long id;
        public String title;
        public int status;              // DownloadManager.STATUS_*
        public long bytesTotal;
        public long bytesDownloaded;
        public int reason;              // always ERROR_UNKNOWN now
        public String mime;
    }

    private static class DownloadAdapter extends ArrayAdapter<DownloadItem> {

        DownloadAdapter(Context ctx) {
            super(ctx, 0, new ArrayList<>());
        }

        void setItems(List<DownloadItem> items) {
            clear();
            if (items != null) addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            Context ctx = getContext();
            float density = ctx.getResources().getDisplayMetrics().density;

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) (16 * density), (int) (12 * density),
                    (int) (16 * density), (int) (12 * density));

            DownloadItem item = getItem(position);
            if (item == null) return row;

            TextView title = new TextView(ctx);
            title.setText(item.title != null ? item.title : "(unnamed)");
            title.setTextSize(15);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(title);

            TextView status = new TextView(ctx);
            status.setTextSize(12);
            status.setText(describeStatus(item));
            status.setPadding(0, (int) (4 * density), 0, 0);
            row.addView(status);

            return row;
        }

        private static String describeStatus(DownloadItem item) {
            switch (item.status) {
                case DownloadManager.STATUS_PENDING:
                    return "Waiting to start";
                case DownloadManager.STATUS_RUNNING:
                    if (item.bytesTotal > 0) {
                        int pct = (int) (item.bytesDownloaded * 100L / item.bytesTotal);
                        return pct + "% of " + humanSize(item.bytesTotal);
                    }
                    return "Downloading " + humanSize(item.bytesDownloaded);
                case DownloadManager.STATUS_PAUSED:
                    return "Paused";
                case DownloadManager.STATUS_SUCCESSFUL:
                    return "Complete (" + humanSize(item.bytesTotal) + ")";
                case DownloadManager.STATUS_FAILED:
                    return "Failed";
                default:
                    return "Unknown";
            }
        }
    }

    @NonNull
    private static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        if (bytes < 1024L * 1024 * 1024 * 1024)
            return String.format(Locale.US, "%.1f GB",
                    bytes / (double) (1024L * 1024 * 1024));
        return String.format(Locale.US, "%.1f TB",
                bytes / (double) (1024L * 1024 * 1024 * 1024));
    }
}
