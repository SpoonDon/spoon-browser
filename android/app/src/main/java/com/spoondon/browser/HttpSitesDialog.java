package com.spoondon.browser;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allow-HTTP-sites manager. Same visual language as the other polished
 * menus. Lists user-added cleartext hosts, with a persistent "Add site"
 * row at the bottom. Long-press a host row to remove it.
 *
 * Instance-based so the host list can be refreshed in place after an
 * add or remove (no dialog dismiss/re-show flicker).
 */
public class HttpSitesDialog {

    private static final float MAX_WIDTH_FRACTION  = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.85f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int COLOR_SURFACE       = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY  = 0xFFEDEDED;
    private static final int COLOR_TEXT_MUTED    = 0xFF8E8E93;
    private static final int COLOR_ICON          = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER       = 0xFF2C2C2E;
    private static final int COLOR_ACCENT        = 0xFF4D6BFE;

    private static final int ROW_PAD_H_DP = 16;
    private static final int ROW_PAD_V_DP = 11;
    private static final int ICON_SIZE_DP = 20;
    private static final int ICON_GAP_DP  = 16;

    private final Context ctx;
    private final LinearLayout listContainer;
    private Dialog dialog;

    public static void show(@NonNull Context ctx) {
        new HttpSitesDialog(ctx).build();
    }

    private HttpSitesDialog(@NonNull Context ctx) {
        this.ctx = ctx;
        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        this.listContainer = new LinearLayout(themed);
        listContainer.setOrientation(LinearLayout.VERTICAL);
    }

    private void build() {
        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout outerRoot = new LinearLayout(themed);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackground(roundedBackground());
        outerRoot.setPadding(0, dp(themed, 6), 0, dp(themed, 6));

        // Scrollable list region
        MaxSizeScrollView scroll = new MaxSizeScrollView(themed);
        scroll.addView(listContainer);
        scroll.setClipToOutline(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        outerRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Divider
        View div = new View(themed);
        div.setBackgroundColor(COLOR_DIVIDER);
        outerRoot.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(themed, 1)));

        // Add row at bottom
        View addRow = buildRow(themed, R.drawable.ic_menu_new_tab,
                "Add site", null);
        addRow.setOnClickListener(v -> showAddSiteDialog());
        outerRoot.addView(addRow);

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

        // Two-pass measure: measure natural content width from the widest row
        // in the Add row (Add is always present and has a good minimum width).
        // Then apply to the outer container and re-measure.
        outerRoot.measure(
                View.MeasureSpec.makeMeasureSpec(capWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,          View.MeasureSpec.UNSPECIFIED));

        MaxSizeScrollView outerScroll = new MaxSizeScrollView(themed);
        outerScroll.addView(outerRoot);
        outerScroll.setBackground(roundedBackground());
        outerScroll.setClipToOutline(true);
        outerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        outerScroll.setMaxWidth(capWidthPx);
        outerScroll.setMaxHeight(capHeightPx);

        dialog = new Dialog(themed);
        dialog.setContentView(outerScroll);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                             ViewGroup.LayoutParams.WRAP_CONTENT);

            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = dp(ctx, SIDE_MARGIN_DP);
            params.y = dp(ctx, TOP_MARGIN_DP);
            window.setAttributes(params);

            View decor = window.getDecorView();
            if (decor != null) {
                decor.setMinimumWidth(0);
                decor.setMinimumHeight(0);
            }
        }

        refresh();
        dialog.show();
    }

    private void refresh() {
        listContainer.removeAllViews();

        Set<String> hosts = CleartextPreferences.getUserHosts(ctx);
        if (hosts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No sites added yet.");
            empty.setTextSize(13);
            empty.setTextColor(COLOR_TEXT_MUTED);
            empty.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP),
                    dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP));
            listContainer.addView(empty);
            return;
        }

        List<String> sorted = new ArrayList<>(hosts);
        Collections.sort(sorted);

        for (String host : sorted) {
            View row = buildRow(ctx, R.drawable.ic_settings_http, host, null);
            row.setOnClickListener(v ->
                    Toast.makeText(ctx, host, Toast.LENGTH_SHORT).show());
            row.setOnLongClickListener(v -> {
                showRemoveConfirmation(host);
                return true;
            });
            listContainer.addView(row);
        }
    }

    private void showAddSiteDialog() {
        final EditText input = new EditText(ctx);
        input.setHint("192.168.1.100  or  router.example.com");
        input.setSingleLine(true);

        new AlertDialog.Builder(ctx)
                .setTitle("Add site")
                .setMessage("HTTP loads for this site will not be upgraded to HTTPS.")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;

                    String normalized = raw.toLowerCase(Locale.ROOT);
                    if (normalized.contains("://")) {
                        normalized = normalized.substring(normalized.indexOf("://") + 3);
                    }
                    if (normalized.contains("/")) {
                        normalized = normalized.substring(0, normalized.indexOf('/'));
                    }
                    if (normalized.contains(":")) {
                        normalized = normalized.split(":")[0];
                    }
                    normalized = normalized.trim();
                    if (normalized.isEmpty()) return;

                    CleartextPreferences.addUserHost(ctx, normalized);
                    Toast.makeText(ctx, "Added " + normalized,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRemoveConfirmation(String host) {
        new AlertDialog.Builder(ctx)
                .setTitle("Remove trusted site?")
                .setMessage(host)
                .setPositiveButton("Remove", (d, w) -> {
                    CleartextPreferences.removeUserHost(ctx, host);
                    Toast.makeText(ctx, "Removed " + host,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View buildRow(@NonNull Context ctx,
                          @DrawableRes int iconRes,
                          @NonNull String title,
                          View trailing) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP),
                       dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP));
        row.setBackgroundResource(selectableItemBackground(ctx));
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(COLOR_ICON);
        row.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, ICON_SIZE_DP), dp(ctx, ICON_SIZE_DP)));

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(COLOR_TEXT_PRIMARY);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(ctx, ICON_GAP_DP));
        row.addView(label, labelLp);

        if (trailing != null) {
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.setMarginStart(dp(ctx, 12));
            row.addView(trailing, tLp);
        }
        return row;
    }

    private GradientDrawable roundedBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_SURFACE);
        g.setCornerRadius(28f);
        return g;
    }

    private int selectableItemBackground(@NonNull Context ctx) {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private int dp(@NonNull Context ctx, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, ctx.getResources().getDisplayMetrics());
    }

    private static class MaxSizeScrollView extends ScrollView {
        private int maxWidthPx  = Integer.MAX_VALUE;
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxSizeScrollView(@NonNull Context context) { super(context); }
        void setMaxWidth(int px)  { this.maxWidthPx = px; requestLayout(); }
        void setMaxHeight(int px) { this.maxHeightPx = px; requestLayout(); }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    capSpec(widthMeasureSpec, maxWidthPx),
                    capSpec(heightMeasureSpec, maxHeightPx));
        }
        private static int capSpec(int spec, int cap) {
            if (MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED) {
                return MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
            if (MeasureSpec.getSize(spec) > cap) {
                return MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
            return spec;
        }
    }
}
