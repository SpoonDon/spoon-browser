package com.spoondon.browser;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import java.util.Collections;
import java.util.List;

/**
 * Owns every DownloadManager interaction.
 *
 * Responsibilities:
 *   - enqueue(url, ua, mime, filename, referer) with proper headers
 *   - register/unregister a BroadcastReceiver for ACTION_DOWNLOAD_COMPLETE
 *   - show a live downloads list with progress, complete/failed states
 *   - open / share / remove a download
 *   - request POST_NOTIFICATIONS on API 33+ so system notifications appear
 *
 * Threading: all public methods must be called on the main thread.
 */
public class DownloadsController {

    private final MainActivity activity;
    private final DownloadManager downloadManager;

    private BroadcastReceiver completionReceiver;
    private boolean receiverRegistered = false;

    private AlertDialog activeDialog;
    private DownloadAdapter activeAdapter;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    public DownloadsController(@NonNull MainActivity activity) {
        this.activity = activity;
        this.downloadManager = (DownloadManager)
                activity.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    // ========================================================================
    // Lifecycle (called from MainActivity)
    // ========================================================================

    public void register() {
        if (receiverRegistered) return;

        if (completionReceiver == null) {
            completionReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != -1) onDownloadComplete(id);
                }
            };
        }

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(completionReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(completionReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    public void unregister() {
        if (!receiverRegistered || completionReceiver == null) return;
        try {
            activity.unregisterReceiver(completionReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    // ========================================================================
    // Enqueue
    // ========================================================================

    /**
     * Start a download. Returns the DownloadManager id, or -1 if it could
     * not be started. Never throws.
     */
    public long enqueue(@NonNull String url,
                        @Nullable String userAgent,
                        @Nullable String mime,
                        @NonNull String fileName,
                        @Nullable String referer) {
        if (downloadManager == null) {
            Toast.makeText(activity, "DownloadManager unavailable", Toast.LENGTH_SHORT).show();
            return -1;
        }

        String safeName = sanitizeFileName(fileName);
        String safeMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream"
                : mime;

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

            String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            if (referer != null && !referer.isEmpty()) {
                request.addRequestHeader("Referer", referer);
            }

            request.setMimeType(safeMime);
            request.setTitle(safeName);
            request.setDescription(url);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, safeName);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            long id = downloadManager.enqueue(request);

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

    @NonNull
    private static String sanitizeFileName(@NonNull String name) {
        String s = name;
        s = s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        s = s.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        s = s.replaceAll("_{2,}", "_");
        if (s.isEmpty()) s = "download";
        if (s.length() > 180) {
            String ext = "";
            int dot = s.lastIndexOf('.');
            if (dot > 0 && s.length() - dot <= 10) {
                ext = s.substring(dot);
            }
            s = s.substring(0, 180 - ext.length()) + ext;
        }
        return s;
    }

    // ========================================================================
    // Query
    // ========================================================================

    @NonNull
    public List<DownloadItem> queryAll() {
        List<DownloadItem> items = new ArrayList<>();
        if (downloadManager == null) return items;

        Cursor c = null;
        try {
            // DownloadManager.query(Query) — passing null returns every
            // download this app has initiated.
            c = downloadManager.query(null);
            if (c == null) return items;

            int idCol = c.getColumnIndex(DownloadManager.COLUMN_ID);
            int titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
            int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            int totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            int soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            int reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON);
            int mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);

            while (c.moveToNext()) {
                DownloadItem item = new DownloadItem();
                item.id = idCol >= 0 ? c.getLong(idCol) : -1;
                item.title = titleCol >= 0 ? c.getString(titleCol) : null;
                item.status = statusCol >= 0 ? c.getInt(statusCol) : 0;
                item.bytesTotal = totalCol >= 0 ? c.getLong(totalCol) : 0;
                item.bytesDownloaded = soFarCol >= 0 ? c.getLong(soFarCol) : 0;
                item.reason = reasonCol >= 0 ? c.getInt(reasonCol) : 0;
                item.mime = mimeCol >= 0 ? c.getString(mimeCol) : null;
                items.add(item);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
        Collections.reverse(items);
        return items;
    }

    @Nullable
    public DownloadItem findById(long id) {
        for (DownloadItem it : queryAll()) {
            if (it.id == id) return it;
        }
        return null;
    }

    // ========================================================================
    // Actions
    // ========================================================================

    public void open(long id) {
        if (downloadManager == null) return;
        Uri uri = downloadManager.getUriForDownloadedFile(id);
        if (uri == null) {
            Toast.makeText(activity, "File not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadItem item = findById(id);
        String mime = (item != null && item.mime != null) ? item.mime : "*/*";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "No app to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    public void share(long id) {
        if (downloadManager == null) return;
        Uri uri = downloadManager.getUriForDownloadedFile(id);
        if (uri == null) {
            Toast.makeText(activity, "File not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        DownloadItem item = findById(id);
        String mime = (item != null && item.mime != null) ? item.mime : "*/*";
        try {
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Share download"));
        } catch (Exception e) {
            Toast.makeText(activity, "No app to share with", Toast.LENGTH_SHORT).show();
        }
    }

    public void remove(long id) {
        if (downloadManager == null) return;
        try {
            downloadManager.remove(id);
        } catch (Exception ignored) {}
    }

    // ========================================================================
    // Completion callback
    // ========================================================================

    private void onDownloadComplete(long id) {
        DownloadItem item = findById(id);
        if (item == null) return;

        String msg;
        switch (item.status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                msg = "Download complete: " + item.title;
                break;
            case DownloadManager.STATUS_FAILED:
                msg = "Download failed: " + item.title
                        + " (" + describeReason(item.reason) + ")";
                break;
            default:
                return;
        }
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();

        if (activeDialog != null && activeDialog.isShowing() && activeAdapter != null) {
            activeAdapter.setItems(queryAll());
        }
    }

    // ========================================================================
    // Dialog
    // ========================================================================

    public void showDownloadsDialog() {
        List<DownloadItem> items = queryAll();

        if (items.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Downloads")
                    .setMessage("No downloads yet.")
                    .setPositiveButton("System downloads",
                            (d, w) -> openSystemDownloads())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        activeAdapter = new DownloadAdapter(activity);
        activeAdapter.setItems(items);

        ListView listView = new ListView(activity);
        listView.setAdapter(activeAdapter);

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            DownloadItem item = activeAdapter.getItem(pos);
            if (item == null) return;
            if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                open(item.id);
            } else {
                showItemOptions(item);
            }
        });

        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            DownloadItem item = activeAdapter.getItem(pos);
            if (item != null) showItemOptions(item);
            return true;
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloads")
                .setView(listView)
                .setPositiveButton("System downloads", (d, w) -> openSystemDownloads())
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

    private void showItemOptions(DownloadItem item) {
        List<String> options = new ArrayList<>();
        if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
            options.add("Open");
            options.add("Share");
        } else if (item.status == DownloadManager.STATUS_FAILED) {
            options.add("Details");
        } else {
            options.add("Cancel");
        }
        options.add("Remove from list");

        String[] arr = options.toArray(new String[0]);
        new AlertDialog.Builder(activity)
                .setTitle(item.title != null ? item.title : "(unnamed)")
                .setItems(arr, (d, which) -> {
                    String choice = arr[which];
                    switch (choice) {
                        case "Open":
                            open(item.id);
                            break;
                        case "Share":
                            share(item.id);
                            break;
                        case "Details":
                            new AlertDialog.Builder(activity)
                                    .setTitle("Download failed")
                                    .setMessage("Reason: " + describeReason(item.reason)
                                            + "\n\nCode: " + item.reason)
                                    .setPositiveButton("OK", null)
                                    .show();
                            break;
                        case "Cancel":
                        case "Remove from list":
                            remove(item.id);
                            if (activeAdapter != null) {
                                activeAdapter.setItems(queryAll());
                            }
                            break;
                    }
                })
                .show();
    }

    private void openSystemDownloads() {
        try {
            Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "No download manager found", Toast.LENGTH_SHORT).show();
        }
    }

    // ========================================================================
    // Reason mapping
    // ========================================================================

    private static String describeReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:       return "cannot resume";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:    return "storage unavailable";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "file already exists";
            case DownloadManager.ERROR_FILE_ERROR:          return "file write error";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:     return "HTTP data error";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:  return "not enough space";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:  return "too many redirects";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "HTTP " + reason;
            case DownloadManager.ERROR_UNKNOWN:             return "unknown error";
            default:                                        return "error code " + reason;
        }
    }

    // ========================================================================
    // Item
    // ========================================================================

    public static class DownloadItem {
        public long id;
        public String title;
        public int status;
        public long bytesTotal;
        public long bytesDownloaded;
        public int reason;
        public String mime;
    }

    // ========================================================================
    // Adapter
    // ========================================================================

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
                    return "Failed (" + describeReason(item.reason) + ")";
                default:
                    return "Unknown";
            }
        }

        private static String describeReason(int reason) {
            switch (reason) {
                case DownloadManager.ERROR_CANNOT_RESUME:       return "cannot resume";
                case DownloadManager.ERROR_DEVICE_NOT_FOUND:    return "storage unavailable";
                case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "file exists";
                case DownloadManager.ERROR_FILE_ERROR:          return "file error";
                case DownloadManager.ERROR_HTTP_DATA_ERROR:     return "HTTP error";
                case DownloadManager.ERROR_INSUFFICIENT_SPACE:  return "no space";
                case DownloadManager.ERROR_TOO_MANY_REDIRECTS:  return "redirect loop";
                case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "HTTP " + reason;
                case DownloadManager.ERROR_UNKNOWN:             return "unknown";
                default:                                        return "code " + reason;
            }
        }

        private static String humanSize(long bytes) {
            if (bytes < 0) return "?";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024L * 1024) return (bytes / 1024) + " KB";
            if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
            return (bytes / (1024L * 1024 * 1024
