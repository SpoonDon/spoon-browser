package com.spoondon.browser;

import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
 * 2026-10-03 UI modernization:
 *   - Dialog chrome now matches MainMenuDialog / ItemManagerDialog v2:
 *     plain Dialog (not AlertDialog), custom header (title + count badge
 *     + ✕), dark row dividers (#2C2C2E), custom per-row StateListDrawable
 *     press feedback, full-width footer action row.
 *   - Active rows show a thin blue (#4D6BFE) progress bar.
 *   - Status line uses a glyph prefix: down-arrow / check / pause / x.
 *   - Empty state renders inside the dialog (no separate AlertDialog).
 *   - enqueue() signature, open/share/pause/resume/remove(), polling
 *     loop, DownloadItem shape all unchanged.
 */
public final class DownloadsController {

    // ------- design tokens (kept local so this file is self-contained) -------
    private static final int COLOR_TEXT      = 0xFFEDEDED;
    private static final int COLOR_TEXT_DIM  = 0xFF9A9A9A;
    private static final int COLOR_ACCENT    = 0xFF4D6BFE;
    private static final int COLOR_DIVIDER   = 0xFF2C2C2E;
    private static final int COLOR_PRESS     = 0x1AFFFFFF; // 10% white
    private static final int COLOR_SUCCESS   = 0xFF4CAF50;
    private static final int COLOR_WARN      = 0xFFFFB74D;
    private static final int COLOR_ERROR     = 0xFFE57373;

    private static final int DIALOG_MAX_WIDTH_DP  = 460;
    private static final int DIALOG_MAX_HEIGHT_DP = 560;

    private final MainActivity activity;
    private final DownloadEngine engine;

    private Dialog activeDialog;
    private DownloadAdapter activeAdapter;
    private TextView headerCountView;
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
    // Enqueue — unchanged signature
    // ------------------------------------------------------------------------

    public long enqueue(@NonNull String url,
                        @Nullable String userAgent,
                        @Nullable String mime,
                        @Nullable String contentDisposition,
                        @Nullable String callerFileName,
                        @Nullable String referer) {
        try {
            String resolved = DownloadNaming.resolve(url, contentDisposition, mime);

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
    // Query
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
    // Actions
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
        refreshIfOpen();
    }

    public void pause(long id) {
        engine.pause(id);
        refreshIfOpen();
    }

    public void resume(long id) {
        engine.resume(id);
        DownloadService.ensureRunning(activity);
        refreshIfOpen();
    }

    // ------------------------------------------------------------------------
    // Shared helpers used by P2
    // ------------------------------------------------------------------------

    private void refreshIfOpen() {
        if (activeAdapter != null) {
            List<DownloadItem> items = queryAll();
            activeAdapter.setItems(items);
            updateHeaderCount(items.size());
        }
    }

    private void updateHeaderCount(int n) {
        if (headerCountView == null) return;
        headerCountView.setText(n == 0 ? "" : String.valueOf(n));
        headerCountView.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
    }

    private static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    private static int dpi(Context ctx, float v) {
        return Math.round(dp(ctx, v));
    }

    // ------------------------------------------------------------------------
    // Dialog — modernized chrome
    // ------------------------------------------------------------------------

    public void showDownloadsDialog() {
        DownloadService.ensureRunning(activity);
        List<DownloadItem> items = queryAll();

        Context ctx = activity;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xFF1B1B1D);
        rootBg.setCornerRadius(dpi(ctx, 14));
        root.setBackground(rootBg);

        // ---- header ----
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpi(ctx, 18), dpi(ctx, 14), dpi(ctx, 12), dpi(ctx, 12));

        TextView title = new TextView(ctx);
        title.setText("Downloads");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        headerCountView = new TextView(ctx);
        headerCountView.setTextColor(COLOR_ACCENT);
        headerCountView.setTextSize(12);
        headerCountView.setPadding(dpi(ctx, 8), dpi(ctx, 2), dpi(ctx, 8), dpi(ctx, 2));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(0x1A4D6BFE);
        badge.setCornerRadius(dpi(ctx, 10));
        headerCountView.setBackground(badge);
        headerCountView.setVisibility(View.GONE);
        header.addView(headerCountView);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(COLOR_TEXT_DIM);
        close.setTextSize(16);
        close.setPadding(dpi(ctx, 14), dpi(ctx, 6), dpi(ctx, 4), dpi(ctx, 6));
        close.setOnClickListener(v -> { if (activeDialog != null) activeDialog.dismiss(); });
        header.addView(close);

        root.addView(header);
        root.addView(makeDivider(ctx));

        // ---- body ----
        FrameLayout body = new FrameLayout(ctx);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (items.isEmpty()) {
            body.addView(buildEmptyState(ctx));
        } else {
            ListView listView = new ListView(ctx);
            listView.setDivider(new ColorDrawable(COLOR_DIVIDER));
            listView.setDividerHeight(Math.max(1, dpi(ctx, 0.5f)));
            listView.setBackgroundColor(0xFF1B1B1D);
            listView.setVerticalScrollBarEnabled(false);
            listView.setCacheColorHint(0);

            activeAdapter = new DownloadAdapter(ctx);
            activeAdapter.setItems(items);
            listView.setAdapter(activeAdapter);

            listView.setOnItemClickListener((p, v, pos, id) -> {
                DownloadItem it = activeAdapter.getItem(pos);
                if (it != null) onItemTapped(it);
            });
            listView.setOnItemLongClickListener((p, v, pos, id) -> {
                DownloadItem it = activeAdapter.getItem(pos);
                if (it != null) showItemOptions(it);
                return true;
            });
            body.addView(listView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        updateHeaderCount(items.size());

        // ---- footer ----
        root.addView(makeDivider(ctx));

        TextView clearBtn = new TextView(ctx);
        clearBtn.setText("Clear finished");
        clearBtn.setTextColor(COLOR_ACCENT);
        clearBtn.setTextSize(14);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(dpi(ctx, 16), dpi(ctx, 14), dpi(ctx, 16), dpi(ctx, 14));
        clearBtn.setBackground(buildRowBackground(ctx));
        clearBtn.setOnClickListener(v -> clearFinished());
        root.addView(clearBtn);

        // ---- Dialog ----
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.55f;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
            w.setLayout(dpi(ctx, DIALOG_MAX_WIDTH_DP), dpi(ctx, DIALOG_MAX_HEIGHT_DP));
        }
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        }

        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            activeDialog = null;
            activeAdapter = null;
            headerCountView = null;
            stopPolling();
        });
        dialog.show();

        startPolling();
    }

    private View makeDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(COLOR_DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpi(ctx, 0.5f))));
        return v;
    }

    private View buildEmptyState(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dpi(ctx, 24), dpi(ctx, 48), dpi(ctx, 24), dpi(ctx, 48));

        TextView glyph = new TextView(ctx);
        glyph.setText("↓");
        glyph.setTextColor(COLOR_TEXT_DIM);
        glyph.setTextSize(34);
        glyph.setGravity(Gravity.CENTER);
        box.addView(glyph);

        TextView msg = new TextView(ctx);
        msg.setText("No downloads yet");
        msg.setTextColor(COLOR_TEXT_DIM);
        msg.setTextSize(14);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpi(ctx, 12);
        box.addView(msg, lp);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER;
        box.setLayoutParams(flp);
        return box;
    }

    private static StateListDrawable buildRowBackground(Context ctx) {
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(COLOR_PRESS);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{android.R.attr.state_focused}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    // ------------------------------------------------------------------------
    // Tap handling
    // ------------------------------------------------------------------------

    private void onItemTapped(@NonNull DownloadItem item) {
        DownloadTask t = engine.getTask(item.id);
        if (t == null) return;
        switch (t.getState()) {
            case COMPLETED: open(item.id); break;
            case RUNNING:
            case QUEUED:    pause(item.id); break;
            case PAUSED:    resume(item.id); break;
            case FAILED:
            case CANCELLED: showItemOptions(item); break;
        }
    }

    private void clearFinished() {
        for (DownloadTask t : engine.getAll()) {
            DownloadTask.State s = t.getState();
            if (s == DownloadTask.State.COMPLETED
                    || s == DownloadTask.State.FAILED
                    || s == DownloadTask.State.CANCELLED) {
                engine.remove(t.getId());
            }
        }
        refreshIfOpen();
    }

    private void startPolling() {
        stopPolling();
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeDialog != null && activeDialog.isShowing() && activeAdapter != null) {
                    List<DownloadItem> items = queryAll();
                    activeAdapter.setItems(items);
                    updateHeaderCount(items.size());
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
    // Context menu — routed through SpoonDialog
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

        SpoonDialog.message(activity, "Download details",
                sb.toString(), "OK", null, () -> { });
    }

    // ------------------------------------------------------------------------
    // Item + Adapter
    // ------------------------------------------------------------------------

    public static class DownloadItem {
        public long id;
        public String title;
        public int status;
        public long bytesTotal;
        public long bytesDownloaded;
        public int reason;
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

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dpi(ctx, 18), dpi(ctx, 12), dpi(ctx, 18), dpi(ctx, 12));
            row.setBackground(buildRowBackground(ctx));

            DownloadItem item = getItem(position);
            if (item == null) return row;

            TextView title = new TextView(ctx);
            title.setText(item.title != null ? item.title : "(unnamed)");
            title.setTextColor(COLOR_TEXT);
            title.setTextSize(14);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(title);

            LinearLayout statusRow = new LinearLayout(ctx);
            statusRow.setOrientation(LinearLayout.HORIZONTAL);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dpi(ctx, 4);
            row.addView(statusRow, slp);

            TextView glyph = new TextView(ctx);
            glyph.setText(statusGlyph(item.status));
            glyph.setTextColor(statusColor(item.status));
            glyph.setTextSize(12);
            glyph.setPadding(0, 0, dpi(ctx, 6), 0);
            statusRow.addView(glyph);

            TextView status = new TextView(ctx);
            status.setText(describeStatus(item));
            status.setTextColor(COLOR_TEXT_DIM);
            status.setTextSize(12);
            status.setSingleLine(true);
            status.setEllipsize(TextUtils.TruncateAt.END);
            statusRow.addView(status, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (item.status == DownloadManager.STATUS_RUNNING
                    || item.status == DownloadManager.STATUS_PENDING) {
                ProgressBar pb = new ProgressBar(ctx, null,
                        android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                int pct;
                if (item.bytesTotal > 0) {
                    pct = (int) (item.bytesDownloaded * 100L / item.bytesTotal);
                    if (pct < 0) pct = 0;
                    if (pct > 100) pct = 100;
                } else {
                    pct = 3;
                }
                pb.setProgress(pct);
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
                pb.setProgressBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(COLOR_DIVIDER));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dpi(ctx, 3));
                plp.topMargin = dpi(ctx, 6);
                row.addView(pb, plp);
            }

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

    private static String statusGlyph(int status) {
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL: return "✓";
            case DownloadManager.STATUS_RUNNING:
            case DownloadManager.STATUS_PENDING:    return "↓";
            case DownloadManager.STATUS_PAUSED:     return "⏸";
            case DownloadManager.STATUS_FAILED:     return "✕";
            default:                                return "•";
        }
    }

    private static int statusColor(int status) {
        switch (status) {
            case DownloadManager.STATUS_SUCCESSFUL: return COLOR_SUCCESS;
            case DownloadManager.STATUS_RUNNING:
            case DownloadManager.STATUS_PENDING:    return COLOR_ACCENT;
            case DownloadManager.STATUS_PAUSED:     return COLOR_WARN;
            case DownloadManager.STATUS_FAILED:     return COLOR_ERROR;
            default:                                return COLOR_TEXT_DIM;
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
