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

/**
 * Top-right anchored main menu dialog.
 *
 * 2026-10-03 (v7.1):
 *   - Width fix: custom theme zeroes windowMinWidthMajor/Minor.
 *   - Row width: two-pass measure forces all rows to the natural width of
 *     the widest row, so chevrons / checkmarks align on the same right
 *     edge. Fixes a bug where the first measure pass reported the cap
 *     width (MATCH_PARENT default), not the natural content width.
 *   - Dividers tagged via View.setTag so the first measure pass can
 *     exclude them from width computation.
 */
public class MainMenuDialog {

    public interface Callback {
        void onAction(@NonNull String actionId);
    }

    public static final String ACTION_NEW_TAB              = "new_tab";
    public static final String ACTION_NEW_INCOGNITO        = "new_incognito";
    public static final String ACTION_RELOAD               = "reload";
    public static final String ACTION_FIND_IN_PAGE         = "find_in_page";
    public static final String ACTION_TOGGLE_DESKTOP       = "toggle_desktop";
    public static final String ACTION_BOOKMARKS            = "bookmarks";
    public static final String ACTION_ADD_BOOKMARK         = "add_bookmark";
    public static final String ACTION_HISTORY              = "history";
    public static final String ACTION_DOWNLOADS            = "downloads";
    public static final String ACTION_PASSWORDS            = "passwords";
    public static final String ACTION_AD_BLOCKING          = "ad_blocking";
    public static final String ACTION_SEARCH_ENGINE        = "search_engine";
    public static final String ACTION_CLEARTEXT_HOSTS      = "cleartext_hosts";
    public static final String ACTION_SETTINGS             = "settings";
    public static final String ACTION_ABOUT                = "about";
    public static final String ACTION_EXIT                 = "exit";

    private static final float MAX_WIDTH_FRACTION  = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.85f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int COLOR_SURFACE       = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY  = 0xFFEDEDED;
    private static final int COLOR_ICON          = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER       = 0xFF2C2C2E;
    private static final int COLOR_DANGER_TEXT   = 0xFFFF5A4D;
    private static final int COLOR_ACCENT        = 0xFF4D6BFE;

    private static final int ROW_PAD_H_DP     = 16;
    private static final int ROW_PAD_V_DP     = 11;
    private static final int ICON_SIZE_DP     = 20;
    private static final int ICON_GAP_DP      = 16;
    private static final int CHEVRON_SIZE_SP  = 20;

    /** Marker tag for dividers so the measure pre-pass can skip them. */
    private static final String TAG_DIVIDER = "spoon_menu_divider";

    public static void show(@NonNull Context ctx,
                            boolean desktopOn,
                            boolean adBlockOn,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(0, dp(themed, 6), 0, dp(themed, 6));

        // Group 1 - Navigation
        root.addView(row(themed, ACTION_NEW_TAB,       R.drawable.ic_menu_new_tab,   "New Tab",       cb));
        root.addView(row(themed, ACTION_NEW_INCOGNITO, R.drawable.ic_menu_incognito, "New Incognito", cb));
        root.addView(row(themed, ACTION_RELOAD,        R.drawable.ic_menu_reload,    "Reload",        cb));
        root.addView(divider(themed));

        // Group 2 - View
        root.addView(row(themed, ACTION_FIND_IN_PAGE, R.drawable.ic_menu_search, "Find in Page", cb));
        root.addView(toggle(themed, ACTION_TOGGLE_DESKTOP, R.drawable.ic_menu_desktop, "Desktop Site", desktopOn, cb));
        root.addView(divider(themed));

        // Group 3 - Library
        root.addView(row(themed, ACTION_BOOKMARKS,    R.drawable.ic_menu_bookmark,     "Bookmarks",    cb));
        root.addView(row(themed, ACTION_ADD_BOOKMARK, R.drawable.ic_menu_bookmark_add, "Add Bookmark", cb));
        root.addView(row(themed, ACTION_HISTORY,      R.drawable.ic_menu_history,      "History",      cb));
        root.addView(row(themed, ACTION_DOWNLOADS,    R.drawable.ic_menu_download,     "Downloads",    cb));
        root.addView(divider(themed));

        // Group 4 - Security
        root.addView(row(themed, ACTION_PASSWORDS, R.drawable.ic_menu_key, "Passwords", cb));
        root.addView(toggle(themed, ACTION_AD_BLOCKING, R.drawable.ic_menu_shield, "Ad Blocking", adBlockOn, cb));
        root.addView(divider(themed));

        // Group 5 - Settings (submenu)
        root.addView(submenu(themed, ACTION_SETTINGS, R.drawable.ic_menu_settings, "Settings", cb));
        root.addView(divider(themed));

        // Group 6 - Info
        root.addView(row(themed, ACTION_ABOUT, R.drawable.ic_menu_info, "About", cb));
        root.addView(divider(themed));

        // Group 7 - Exit
        root.addView(dangerRow(themed, ACTION_EXIT, R.drawable.ic_menu_exit, "Exit", cb));

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

        // --- FIRST PASS ---------------------------------------------------
        // Force rows to WRAP_CONTENT so LinearLayout reports the natural
        // maximum width across all rows. Dividers get width 0 so they
        // don't inflate the measurement. Without this, the default
        // MATCH_PARENT params cause every row to fill the AT_MOST spec,
        // so root.getMeasuredWidth() == capWidthPx and the dialog ends up
        // as wide as the cap rather than hugging its content.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                if (TAG_DIVIDER.equals(child.getTag())) {
                    llp.width = 0;
                } else {
                    llp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                }
            }
        }
        root.measure(
                View.MeasureSpec.makeMeasureSpec(capWidthPx, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0,          View.MeasureSpec.UNSPECIFIED));
        int contentWidth = Math.min(root.getMeasuredWidth(), capWidthPx);

        // --- SECOND PASS --------------------------------------------------
        // Pin every child to the natural width so chevrons and checkmarks
        // sit at the same right edge across all rows.
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
        return buildRow(ctx, actionId, iconRes, title, cb, false, null, false);
    }

    private static View dangerRow(@NonNull Context ctx, @NonNull String actionId,
                                  @DrawableRes int iconRes, @NonNull String title,
                                  @NonNull Callback cb) {
        return buildRow(ctx, actionId, iconRes, title, cb, true, null, false);
    }

    private static View submenu(@NonNull Context ctx, @NonNull String actionId,
                                @DrawableRes int iconRes, @NonNull String title,
                                @NonNull Callback cb) {
        return buildRow(ctx, actionId, iconRes, title, cb, false, null, true);
    }

    private static View toggle(@NonNull Context ctx, @NonNull String actionId,
                               @DrawableRes int iconRes, @NonNull String title,
                               boolean initial, @NonNull Callback cb) {
        final boolean[] state = { initial };
        final TextView check = new TextView(ctx);
        check.setText("\u2713");
        check.setTextSize(18);
        check.setTextColor(COLOR_ACCENT);
        check.setVisibility(initial ? View.VISIBLE : View.INVISIBLE);

        View rowView = buildRow(ctx, actionId, iconRes, title, cb, false, check, false);
        rowView.setOnClickListener(v -> {
            state[0] = !state[0];
            check.setVisibility(state[0] ? View.VISIBLE : View.INVISIBLE);
            cb.onAction(actionId);
        });
        return rowView;
    }

    private static View buildRow(@NonNull Context ctx, @NonNull String actionId,
                                 @DrawableRes int iconRes, @NonNull String title,
                                 @NonNull Callback cb, boolean danger,
                                 View trailing, boolean chevron) {

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
        icon.setColorFilter(danger ? COLOR_DANGER_TEXT : COLOR_ICON);
        row.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, ICON_SIZE_DP), dp(ctx, ICON_SIZE_DP)));

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(danger ? COLOR_DANGER_TEXT : COLOR_TEXT_PRIMARY);
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
        } else if (chevron) {
            TextView chev = new TextView(ctx);
            chev.setText("\u203A");
            chev.setTextSize(CHEVRON_SIZE_SP);
            chev.setTextColor(COLOR_ICON);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.setMarginStart(dp(ctx, 12));
            row.addView(chev, cLp);
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
