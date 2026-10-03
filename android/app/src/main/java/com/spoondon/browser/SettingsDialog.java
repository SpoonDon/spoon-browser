package com.spoondon.browser;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Settings submenu dialog. Same visual language as {@link MainMenuDialog}:
 * same theme, same top-right anchor, same two-pass measure, same divider
 * treatment. Rows are icons + labels + optional trailing view.
 *
 * 2026-10-03 (v3):
 *   - Added "Site allowlist" row under Manage filter lists. Ad Blocking
 *     on the main menu became a pure toggle (Option A); its sub-items
 *     redistributed here — Manage filter lists already existed, Site
 *     allowlist is new.
 *
 * 2026-10-03 (v2):
 *   - Startup animation is now a proper toggle row with a checkmark when on.
 *   - buildRow gained a trailing-view slot for the checkmark.
 */
public class SettingsDialog {

    public interface Callback {
        void onAction(@NonNull String actionId);
    }

    public static final String ACTION_SEARCH_ENGINE      = "settings_search_engine";
    public static final String ACTION_ALLOW_HTTP         = "settings_allow_http";
    public static final String ACTION_CLEAR_CACHE        = "settings_clear_cache";
    public static final String ACTION_CLEAR_HISTORY      = "settings_clear_history";
    public static final String ACTION_STARTUP_ANIMATION  = "settings_startup_animation";
    public static final String ACTION_FILTER_LISTS       = "settings_filter_lists";
    public static final String ACTION_SITE_ALLOWLIST     = "settings_site_allowlist";
    public static final String ACTION_IMPORT_PASSWORDS   = "settings_import_passwords";
    public static final String ACTION_EXPORT_PASSWORDS   = "settings_export_passwords";

    private static final float MAX_WIDTH_FRACTION  = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.85f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int COLOR_SURFACE       = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY  = 0xFFEDEDED;
    private static final int COLOR_ICON          = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER       = 0xFF2C2C2E;
    private static final int COLOR_ACCENT        = 0xFF4D6BFE;

    private static final int ROW_PAD_H_DP     = 16;
    private static final int ROW_PAD_V_DP     = 11;
    private static final int ICON_SIZE_DP     = 20;
    private static final int ICON_GAP_DP      = 16;

    private static final String TAG_DIVIDER = "spoon_settings_divider";

    public static void show(@NonNull Context ctx,
                            boolean startupAnimationOn,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(0, dp(themed, 6), 0, dp(themed, 6));

        root.addView(row(themed, ACTION_SEARCH_ENGINE, R.drawable.ic_menu_search,
                "Search engine", cb));
        root.addView(row(themed, ACTION_ALLOW_HTTP, R.drawable.ic_settings_http,
                "Allow HTTP sites", cb));
        root.addView(divider(themed));

        root.addView(row(themed, ACTION_CLEAR_CACHE, R.drawable.ic_settings_clear_cache,
                "Clear cache", cb));
        root.addView(row(themed, ACTION_CLEAR_HISTORY, R.drawable.ic_menu_history,
                "Clear history", cb));
        root.addView(divider(themed));

        root.addView(toggle(themed, ACTION_STARTUP_ANIMATION, R.drawable.ic_settings_animation,
                "Startup animation", startupAnimationOn, cb));
        root.addView(row(themed, ACTION_FILTER_LISTS, R.drawable.ic_menu_shield,
                "Manage filter lists", cb));
        root.addView(row(themed, ACTION_SITE_ALLOWLIST, R.drawable.ic_menu_shield,
                "Site allowlist", cb));
        root.addView(divider(themed));

        root.addView(row(themed, ACTION_IMPORT_PASSWORDS, R.drawable.ic_settings_import,
                "Import passwords (CSV)", cb));
        root.addView(row(themed, ACTION_EXPORT_PASSWORDS, R.drawable.ic_settings_export,
                "Export passwords (CSV)", cb));

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

        // First pass: WRAP_CONTENT on rows, 0 on dividers.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                if (TAG_DIVIDER.equals(child.getTag())) llp.width = 0;
                else llp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        }
        root.measure(
                View.MeasureSpec.makeMeasureSpec(capWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,          View.MeasureSpec.UNSPECIFIED));
        int contentWidth = Math.min(root.getMeasuredWidth(), capWidthPx);

        // Second pass: pin every child to the widest natural width.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null) {
                lp.width = contentWidth;
                child.setLayoutParams(lp);
            }
        }

        MaxSizeScrollView scroll = new MaxSizeScrollView(themed);
        scroll.addView(root);
        scroll.setBackground(roundedBackground());
        scroll.setClipToOutline(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setMaxWidth(capWidthPx);
        scroll.setMaxHeight(capHeightPx);

        Dialog dialog = new Dialog(themed);
        dialog.setContentView(scroll);

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

        dialog.show();
    }

    private static View row(@NonNull Context ctx, @NonNull String actionId,
                            @DrawableRes int iconRes, @NonNull String title,
                            @NonNull Callback cb) {
        return buildRow(ctx, actionId, iconRes, title, cb, null);
    }

    private static View toggle(@NonNull Context ctx, @NonNull String actionId,
                               @DrawableRes int iconRes, @NonNull String title,
                               boolean initial, @NonNull Callback cb) {
        final boolean[] state = { initial };

        final TextView check = new TextView(ctx);
        check.setText("\u2713"); // checkmark
        check.setTextSize(18);
        check.setTextColor(COLOR_ACCENT);
        check.setVisibility(initial ? View.VISIBLE : View.INVISIBLE);

        View rowView = buildRow(ctx, actionId, iconRes, title, cb, check);
        rowView.setOnClickListener(v -> {
            state[0] = !state[0];
            check.setVisibility(state[0] ? View.VISIBLE : View.INVISIBLE);
            cb.onAction(actionId);
        });
        return rowView;
    }

    private static View buildRow(@NonNull Context ctx,
                                 @NonNull String actionId,
                                 @DrawableRes int iconRes,
                                 @NonNull String title,
                                 @NonNull Callback cb,
                                 @Nullable View trailing) {
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

        row.setOnClickListener(v -> cb.onAction(actionId));
        return row;
    }

    private static View divider(@NonNull Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(COLOR_DIVIDER);
        v.setTag(TAG_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        v.setLayoutParams(lp);
        return v;
    }

    private static GradientDrawable roundedBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_SURFACE);
        g.setCornerRadius(28f);
        return g;
    }

    private static int selectableItemBackground(@NonNull Context ctx) {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private static int dp(@NonNull Context ctx, int value) {
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
