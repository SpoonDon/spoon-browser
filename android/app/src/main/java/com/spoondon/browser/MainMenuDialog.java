package com.spoondon.browser;

import android.app.AlertDialog;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Custom main-menu dialog anchored to the top-right corner.
 *
 * 2026-10-02 (v5):
 *   - Menu shortened: Search Engine and Allow HTTP sites moved into the
 *     Settings submenu. Top-level rows: 15 -> 12.
 *   - Row width no longer stretches via weight=1. Each row is WRAP_CONTENT
 *     with a fixed-width right slot for the toggle checkmark, so all rows
 *     share the same right edge and the dialog hugs the widest label.
 *   - Window gravity applied BEFORE show() so there is no center-flash.
 *   - Row / header padding tightened.
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

    private static final float MAX_WIDTH_FRACTION  = 0.88f;
    private static final float MAX_HEIGHT_FRACTION = 0.80f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    /** Size of the reserved right slot on every row (checkmark home). */
    private static final int RIGHT_SLOT_DP = 24;

    /** Gap between text block and right slot. */
    private static final int RIGHT_SLOT_GAP_DP = 16;

    public static void show(@NonNull Context ctx,
                            boolean desktopOn,
                            boolean adBlockOn,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, android.R.style.Theme_Material_Dialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(dp(themed, 2), dp(themed, 4), dp(themed, 2), dp(themed, 6));

        // --- Navigation -------------------------------------------------
        root.addView(header(themed, "Navigation"));
        root.addView(item(themed, ACTION_NEW_TAB,       "New Tab",       null, cb));
        root.addView(item(themed, ACTION_NEW_INCOGNITO, "New Incognito", null, cb));
        root.addView(item(themed, ACTION_RELOAD,        "Reload",        null, cb));

        // --- View -------------------------------------------------------
        root.addView(header(themed, "View"));
        root.addView(item(themed, ACTION_FIND_IN_PAGE, "Find in Page", null, cb));
        root.addView(toggle(themed, ACTION_TOGGLE_DESKTOP, "Desktop Site", desktopOn, cb));

        // --- Library ----------------------------------------------------
        root.addView(header(themed, "Library"));
        root.addView(item(themed, ACTION_BOOKMARKS,    "Bookmarks",    null, cb));
        root.addView(item(themed, ACTION_ADD_BOOKMARK, "Add Bookmark", null, cb));
        root.addView(item(themed, ACTION_HISTORY,      "History",      null, cb));
        root.addView(item(themed, ACTION_DOWNLOADS,    "Downloads",    null, cb));

        // --- Security ---------------------------------------------------
        root.addView(header(themed, "Security"));
        root.addView(item(themed, ACTION_PASSWORDS,    "Passwords",   null, cb));
        root.addView(toggle(themed, ACTION_AD_BLOCKING, "Ad Blocking", adBlockOn, cb));

        // --- Settings ---------------------------------------------------
        root.addView(header(themed, "Settings"));
        root.addView(item(themed, ACTION_SETTINGS, "Settings ▸", null, cb));

        // --- Info -------------------------------------------------------
        root.addView(header(themed, "Info"));
        root.addView(item(themed, ACTION_ABOUT, "About", null, cb));

        // --- Exit -------------------------------------------------------
        root.addView(spacer(themed));
        root.addView(dangerItem(themed, ACTION_EXIT, "Exit", cb));

        MaxSizeScrollView scroll = new MaxSizeScrollView(themed);
        scroll.addView(root);
        scroll.setBackground(roundedBackground());
        scroll.setClipToOutline(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        AlertDialog dialog = new AlertDialog.Builder(themed)
                .setView(scroll)
                .create();

        // Configure the window BEFORE show() so the dialog is laid out
        // at its final position and final width on the first frame.
        Window window = dialog.getWindow();
        if (window != null) {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int maxWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
            int maxHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

            scroll.setMaxWidth(maxWidthPx);
            scroll.setMaxHeight(maxHeightPx);

            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                             ViewGroup.LayoutParams.WRAP_CONTENT);

            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = dp(ctx, SIDE_MARGIN_DP);
            params.y = dp(ctx, TOP_MARGIN_DP);
            window.setAttributes(params);
        }

        dialog.show();
    }

    // ------------------------------------------------------------------
    // Row builders
    // ------------------------------------------------------------------

    private static TextView header(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text.toUpperCase());
        tv.setTextSize(10);
        tv.setTextColor(0xFF8E8E93);
        tv.setLetterSpacing(0.1f);
        tv.setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 3));
        return tv;
    }

    private static View item(@NonNull Context ctx,
                             @NonNull String actionId,
                             @NonNull String title,
                             String subtitle,
                             @NonNull Callback cb) {
        return buildRow(ctx, actionId, title, subtitle, cb, false, null);
    }

    private static View dangerItem(@NonNull Context ctx,
                                   @NonNull String actionId,
                                   @NonNull String title,
                                   @NonNull Callback cb) {
        return buildRow(ctx, actionId, title, null, cb, true, null);
    }

    private static View toggle(@NonNull Context ctx,
                               @NonNull String actionId,
                               @NonNull String title,
                               boolean initial,
                               @NonNull Callback cb) {
        final boolean[] state = { initial };
        final TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(17);
        check.setTextColor(0xFF4D6BFE);
        check.setVisibility(initial ? View.VISIBLE : View.INVISIBLE);

        View row = buildRow(ctx, actionId, title, null, cb, false, check);
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            check.setVisibility(state[0] ? View.VISIBLE : View.INVISIBLE);
            cb.onAction(actionId);
        });
        return row;
    }

    private static View buildRow(@NonNull Context ctx,
                                 @NonNull String actionId,
                                 @NonNull String title,
                                 String subtitle,
                                 @NonNull Callback cb,
                                 boolean danger,
                                 View trailing) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 8));
        row.setMinimumHeight(dp(ctx, 38));
        row.setBackgroundResource(selectableItemBackground(ctx));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTextColor(danger ? 0xFFFF453A : 0xFFFFFFFF);
        textCol.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(ctx);
            subView.setText(subtitle);
            subView.setTextSize(11);
            subView.setTextColor(0xFF8E8E93);
            subView.setPadding(0, dp(ctx, 1), 0, 0);
            textCol.addView(subView);
        }

        // WRAP_CONTENT text column — no weight. Rows hug their content
        // width; the dialog hugs the widest row.
        row.addView(textCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Fixed-width right slot. Every row reserves it so all rows share
        // the same right edge. Toggle rows place the checkmark inside;
        // other rows leave it empty.
        FrameLayout rightSlot = new FrameLayout(ctx);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                dp(ctx, RIGHT_SLOT_DP), dp(ctx, RIGHT_SLOT_DP));
        slotParams.setMarginStart(dp(ctx, RIGHT_SLOT_GAP_DP));
        row.addView(rightSlot, slotParams);

        if (trailing != null) {
            FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            innerLp.gravity = Gravity.CENTER;
            rightSlot.addView(trailing, innerLp);
        }

        row.setOnClickListener(v -> cb.onAction(actionId));
        return row;
    }

    private static View spacer(@NonNull Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 18), dp(ctx, 6), dp(ctx, 18), dp(ctx, 2));
        v.setLayoutParams(lp);
        v.setBackgroundColor(0xFF2C2C2E);
        return v;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static GradientDrawable roundedBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1C1E);
        g.setCornerRadius(24f);
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

        private int maxWidthPx = Integer.MAX_VALUE;
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxSizeScrollView(@NonNull Context context) {
            super(context);
        }

        void setMaxWidth(int px) { this.maxWidthPx = px; requestLayout(); }
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
