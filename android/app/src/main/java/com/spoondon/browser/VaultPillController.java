package com.spoondon.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.Collections;
import java.util.List;

/**
 * Floating "vault pill" that appears when the current page has a password
 * field and the vault has credentials for the current host. Tapping it
 * opens a bottom sheet with per-field copy buttons so the user can grab
 * both the username and the password without leaving the page.
 *
 * The pill is invisible until JS on the page detects an input[type=password]
 * and calls {@code SpoonVaultUI.showPill()}. The bridge method is bound to
 * the calling WebView — background tabs cannot trigger the pill for a
 * different tab.
 *
 * The sheet stays open across copies. Both fields can be copied without
 * reopening. Clipboard entries auto-clear after 60 seconds, same policy
 * as the vault page.
 *
 * 2026-10-01 (initial): pill + bottom sheet.
 */
public class VaultPillController {

    public interface WebViewProvider {
        @Nullable WebView getCurrentWebView();
    }

    /** How long a copied secret survives on the clipboard. */
    private static final long CLIPBOARD_CLEAR_DELAY_MS = 60_000L;

    private final MainActivity activity;
    private final SecureCredentialManager credentials;
    private final WebViewProvider webViewProvider;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Nullable private FrameLayout host;
    @Nullable private View pillView;
    @Nullable private BottomSheetDialog sheet;
    private boolean sheetVisible = false;
    @Nullable private Runnable pendingClipboardClear;

    public VaultPillController(@NonNull MainActivity activity,
                               @NonNull SecureCredentialManager credentials,
                               @NonNull WebViewProvider webViewProvider) {
        this.activity = activity;
        this.credentials = credentials;
        this.webViewProvider = webViewProvider;
    }

    // ====================================================================
    // Host attachment
    // ====================================================================

    /** Attach (or re-attach) the pill to the given FrameLayout. */
    public void setHost(@Nullable FrameLayout newHost) {
        if (this.host != null && pillView != null && pillView.getParent() == this.host) {
            this.host.removeView(pillView);
        }
        this.host = newHost;
        if (newHost != null) {
            if (pillView == null) pillView = buildPill();
            newHost.addView(pillView, buildPillParams());
            pillView.setVisibility(View.GONE);
        }
    }

    // ====================================================================
    // Bridge entry points (called from VaultUIBridge)
    // ====================================================================

    /**
     * Called from JS when a password field is found on the page. The caller
     * WebView must be the current tab's WebView — a background tab must
     * not pop the pill while a different page is visible.
     */
    public void onPasswordFieldDetected(@Nullable WebView caller) {
        ui.post(() -> {
            WebView current = webViewProvider.getCurrentWebView();
            if (caller == null || current == null || caller != current) return;
            String host = current.getUrl();
            if (host == null) { hidePillInternal(); return; }
            try {
                host = android.net.Uri.parse(host).getHost();
            } catch (Exception e) { hidePillInternal(); return; }
            if (host == null || host.isEmpty()) { hidePillInternal(); return; }

            List<SecureCredentialManager.Credential> creds =
                    credentials.getCredentialsForHost(host);
            if (creds.isEmpty()) { hidePillInternal(); return; }
            showPillInternal();
        });
    }

    /** Called from JS when the password field is removed or the page unloads. */
    public void onPasswordFieldGone() {
        ui.post(this::hidePillInternal);
    }

    /** Called from SWVC on page start — clears stale pill state. */
    public void onPageNavigated() {
        ui.post(() -> {
            hidePillInternal();
            if (sheet != null && sheet.isShowing()) sheet.dismiss();
        });
    }

    // ====================================================================
    // Pill rendering
    // ====================================================================

    private View buildPill() {
        TextView pill = new TextView(activity);
        pill.setText("\uD83D\uDD11"); // 🔑
        pill.setTextSize(22);
        pill.setGravity(Gravity.CENTER);
        pill.setContentDescription("Saved logins for this site");
        pill.setElevation(dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#1F1F22"));
        bg.setStroke(dp(1), Color.parseColor("#4D6BFE"));
        pill.setBackground(bg);

        pill.setOnClickListener(v -> openSheet());

        int size = dp(48);
        pill.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        return pill;
    }

    private FrameLayout.LayoutParams buildPillParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(48), dp(48));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        int margin = dp(16);
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }

    private void showPillInternal() {
        if (pillView == null || sheetVisible) return;
        if (pillView.getVisibility() == View.VISIBLE) return;
        pillView.setVisibility(View.VISIBLE);
        pillView.setAlpha(0f);
        pillView.animate().alpha(1f).setDuration(180).start();
    }

    private void hidePillInternal() {
        if (pillView == null) return;
        if (pillView.getVisibility() != View.VISIBLE) return;
        pillView.animate().alpha(0f).setDuration(140)
                .withEndAction(() -> pillView.setVisibility(View.GONE)).start();
    }

    // ====================================================================
    // Bottom sheet
    // ====================================================================

    private void openSheet() {
        WebView current = webViewProvider.getCurrentWebView();
        if (current == null) return;
        String url = current.getUrl();
        if (url == null) return;
        String host;
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Exception e) {
            return;
        }
        if (host == null || host.isEmpty()) return;

        List<SecureCredentialManager.Credential> creds =
                credentials.getCredentialsForHost(host);
        if (creds.isEmpty()) {
            Toast.makeText(activity, "No saved logins for " + host,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showSheet(host, creds);
    }

    private void showSheet(String host,
                           List<SecureCredentialManager.Credential> creds) {
        final BottomSheetDialog dialog = new BottomSheetDialog(activity);

        // Container lets us swap between list view and detail view in-place
        // without recreating the dialog. Both flows replace all children.
        final FrameLayout container = new FrameLayout(activity);
        container.setBackgroundColor(Color.parseColor("#1C1C1E"));
        dialog.setContentView(container);

        this.sheet = dialog;
        this.sheetVisible = true;

        if (creds.size() == 1) {
            container.addView(buildDetailView(host, creds.get(0), null));
        } else {
            container.addView(buildListView(host, creds, container));
        }

        dialog.setOnDismissListener(d -> {
            this.sheet = null;
            this.sheetVisible = false;
            maybeReshowPill();
        });

        dialog.show();
        hidePillInternal();
    }

    // --------------------------------------------------------------------
    // Views
    // --------------------------------------------------------------------

    private View buildListView(String host,
                               List<SecureCredentialManager.Credential> creds,
                               FrameLayout container) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        root.addView(buildHeader(host, null));

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < creds.size(); i++) {
            final SecureCredentialManager.Credential c = creds.get(i);
            View row = buildAccountRow(host, c, () -> {
                container.removeAllViews();
                container.addView(buildDetailView(host, c, () -> {
                    container.removeAllViews();
                    container.addView(buildListView(host, creds, container));
                }));
            });
            if (i > 0) list.addView(spacer(dp(8)));
            list.addView(row);
        }

        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildDetailView(String host,
                                 SecureCredentialManager.Credential cred,
                                 @Nullable Runnable onBack) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        root.addView(buildHeader(host, onBack));
        root.addView(buildFieldRow("Username", cred.username, false));
        root.addView(spacer(dp(8)));
        root.addView(buildFieldRow("Password", cred.password, true));
        return root;
    }

    private View buildHeader(String host, @Nullable Runnable onBack) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(16));

        if (onBack != null) {
            TextView back = new TextView(activity);
            back.setText("\u2190"); // ←
            back.setTextSize(22);
            back.setTextColor(Color.WHITE);
            back.setPadding(0, dp(4), dp(12), dp(4));
            back.setOnClickListener(v -> onBack.run());
            header.addView(back);
        }

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Saved login");
        title.setTextSize(15);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView subtitle = new TextView(activity);
        subtitle.setText(host);
        subtitle.setTextSize(11);
        subtitle.setTextColor(Color.parseColor("#8E8E93"));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

        col.addView(title);
        col.addView(subtitle);
        header.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(activity);
        close.setText("\u2715"); // ✕
        close.setTextSize(16);
        close.setTextColor(Color.parseColor("#8E8E93"));
        close.setPadding(dp(12), dp(8), 0, dp(8));
        close.setOnClickListener(v -> {
            if (sheet != null) sheet.dismiss();
        });
        header.addView(close);
        return header;
    }

    private View buildAccountRow(String currentHost,
                                 SecureCredentialManager.Credential cred,
                                 Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.parseColor("#2C2C2E"));
        row.setBackground(bg);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView user = new TextView(activity);
        user.setText(cred.username);
        user.setTextSize(15);
        user.setTextColor(Color.WHITE);
        user.setSingleLine(true);
        user.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView hint = new TextView(activity);
        hint.setTextSize(11);
        hint.setTextColor(Color.parseColor("#8E8E93"));
        if (!cred.host.equals(currentHost)) {
            hint.setText("Saved for " + cred.host);
        } else {
            hint.setText("Tap to view");
        }

        col.addView(user);
        col.addView(hint);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(activity);
        chevron.setText("\u203A"); // ›
        chevron.setTextSize(20);
        chevron.setTextColor(Color.parseColor("#8E8E93"));
        row.addView(chevron);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private View buildFieldRow(String label, String value, boolean isSecret) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.parseColor("#2C2C2E"));
        row.setBackground(bg);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextSize(10);
        labelView.setTextColor(Color.parseColor("#8E8E93"));
        labelView.setAllCaps(true);

        final TextView valueView = new TextView(activity);
        valueView.setTextSize(15);
        valueView.setTextColor(Color.WHITE);
        valueView.setSingleLine(true);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        final boolean[] revealed = { !isSecret };
        valueView.setText(revealed[0] ? value : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");

        col.addView(labelView);
        col.addView(valueView);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isSecret) {
            final TextView toggle = new TextView(activity);
            toggle.setText(revealed[0] ? "Hide" : "Show");
            toggle.setTextSize(13);
            toggle.setTextColor(Color.parseColor("#8FB0FF"));
            toggle.setPadding(dp(12), dp(8), dp(8), dp(8));
            toggle.setOnClickListener(v -> {
                revealed[0] = !revealed[0];
                valueView.setText(revealed[0]
                        ? value
                        : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");
                toggle.setText(revealed[0] ? "Hide" : "Show");
            });
            row.addView(toggle);
        }

        TextView copy = new TextView(activity);
        copy.setText("Copy");
        copy.setTextSize(13);
        copy.setTextColor(Color.parseColor("#8FB0FF"));
        copy.setPadding(dp(8), dp(8), dp(4), dp(8));
        copy.setOnClickListener(v -> copyToClipboard(value, label));
        row.addView(copy);

        return row;
    }

    // --------------------------------------------------------------------
    // Post-dismiss behavior
    // --------------------------------------------------------------------

    /**
     * Called after the sheet closes. If the user is still on a login page
     * with credentials, re-show the pill so they can reopen the sheet if
     * they realize they copied the wrong field. If the page navigated while
     * the sheet was open, {@link #onPageNavigated()} has already hidden the
     * pill and this check will naturally no-op.
     */
    private void maybeReshowPill() {
        WebView current = webViewProvider.getCurrentWebView();
        if (current == null) return;
        String url = current.getUrl();
        if (url == null) return;
        String host;
        try {
            host = android.net.Uri.parse(url).getHost();
        } catch (Exception e) {
            return;
        }
        if (host == null || host.isEmpty()) return;
        if (credentials.getCredentialsForHost(host).isEmpty()) return;
        showPillInternal();
    }

    // --------------------------------------------------------------------
    // Copy with 60-second auto-clear
    // --------------------------------------------------------------------

    private void copyToClipboard(String value, String label) {
        if (value == null || value.isEmpty()) {
            Toast.makeText(activity, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;

        cm.setPrimaryClip(ClipData.newPlainText("spoon_vault", value));
        Toast.makeText(activity, label + " copied", Toast.LENGTH_SHORT).show();

        // Auto-clear only if the clipboard still holds our value. If the user
        // copied something else in the meantime, we leave it alone.
        if (pendingClipboardClear != null) ui.removeCallbacks(pendingClipboardClear);
        pendingClipboardClear = () -> {
            try {
                if (cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence now = clip.getItemAt(0).coerceToText(activity);
                        if (value.contentEquals(now)) {
                            if (android.os.Build.VERSION.SDK_INT
                                    >= android.os.Build.VERSION_CODES.P) {
                                cm.clearPrimaryClip();
                            } else {
                                cm.setPrimaryClip(ClipData.newPlainText("", ""));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            pendingClipboardClear = null;
        };
        ui.postDelayed(pendingClipboardClear, CLIPBOARD_CLEAR_DELAY_MS);
    }

    private View spacer(int h) {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    // ====================================================================
    // Small helpers
    // ====================================================================

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
