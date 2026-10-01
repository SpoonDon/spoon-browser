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

    // === PART 2 CONTINUES HERE ===

    // ====================================================================
    // Small helpers
    // ====================================================================

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
