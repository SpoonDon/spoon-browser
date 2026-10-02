package com.spoondon.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Custom main-menu dialog. Built programmatically (no XML, no RecyclerView)
 * because the row list is short, static while open, and benefits more from
 * local cohesion than from recycling.
 *
 * 2026-10-02 (v2): Width is capped at 340dp / 92% of screen. Height is
 * capped at 72% of screen height via {@link MaxHeightScrollView} so the
 * dialog never eats the whole viewport on small displays. Row padding and
 * font sizes tightened to fit more items without scrolling.
 *
 * Row types:
 *   HEADER  — section label, muted, non-tappable
 *   ITEM    — tappable row
 *   TOGGLE  — tappable row with a checkmark on the right; the check
 *             flips locally on tap and the same action id is fired
 *   SPACER  — divider/gap
 *
 * The dialog never reads live state itself — the caller pre-resolves all
 * booleans and passes them in. Every interaction flows back through a
 * single {@link Callback#onAction(String)} method.
 */
public class MainMenuDialog {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public interface Callback {
        void onAction(@NonNull String actionId);
    }

    // Action ids (caller-parsed strings)
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

    /** Upper bound on dialog width regardless of screen size. */
    private static final int MAX_WIDTH_DP = 340;

    /** Fraction of screen height the dialog is allowed to occupy. */
    private static final float MAX_HEIGHT_FRACTION = 0.72f;

    /**
     * Shows the menu.
     *
     * @param ctx         host context
     * @param desktopOn   current desktop-site state for the active tab
     * @param adBlockOn   current ad-block engine state
     * @param cb          callback for every action id
     */
    public static void show(@NonNull Context ctx,
                            boolean desktopOn,
                            boolean adBlockOn,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, android.R.style.Theme_Material_Dialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(dp(themed, 4), dp(themed, 6), dp(themed, 4), dp(themed, 8));

        // --- Navigation -------------------------------------------------
        root.addView(header(themed, "Navigation"));
        root.addView(item(themed, ACTION_NEW_TAB,       "New Tab",           null, cb));
        root.addView(item(themed, ACTION_NEW_INCOGNITO, "New Incognito Tab", null, cb));
        root.addView(item(themed, ACTION_RELOAD,        "Reload",            null, cb));

        // --- View -------------------------------------------------------
        root.addView(header(themed, "View"));
        root.addView(item(themed, ACTION_FIND_IN_PAGE, "Find in Page", null, cb));
        root.addView(toggle(themed, ACTION_TOGGLE_DESKTOP, "Desktop Site",
                desktopOn ? "Loading desktop layout" : "Loading mobile layout",
                desktopOn, cb));

        // --- Library ----------------------------------------------------
        root.addView(header(themed, "Library"));
        root.addView(item(themed, ACTION_BOOKMARKS,    "Bookmarks",     null, cb));
        root.addView(item(themed, ACTION_ADD_BOOKMARK, "Add Bookmark",  null, cb));
        root.addView(item(themed, ACTION_HISTORY,      "History",       null, cb));
        root.addView(item(themed, ACTION_DOWNLOADS,    "Downloads",     null, cb));

        // --- Security ---------------------------------------------------
        root.addView(header(themed, "Security"));
        root.addView(item(themed, ACTION_PASSWORDS,   "Passwords",
                "Open the vault", cb));
        root.addView(toggle(themed, ACTION_AD_BLOCKING, "Ad Blocking",
                adBlockOn ? "Enabled" : "Disabled",
                adBlockOn, cb));

        // --- Settings ---------------------------------------------------
        root.addView(header(themed, "Settings"));
        root.addView(item(themed, ACTION_SEARCH_ENGINE,   "Search Engine",     null, cb));
        root.addView(item(themed, ACTION_CLEARTEXT_HOSTS, "Allow HTTP sites",
                "Routers and local devices", cb));
        root.addView(item(themed, ACTION_SETTINGS, "Settings ▸",
                "Cache, history, startup, passwords", cb));

        // --- Info -------------------------------------------------------
        root.addView(header(themed, "Info"));
        root.addView(item(themed, ACTION_ABOUT, "About", null, cb));

        // --- Exit (danger, isolated) ------------------------------------
        root.addView(spacer(themed));
        root.addView(dangerItem(themed, ACTION_EXIT, "Exit", cb));

        MaxHeightScrollView scroll = new MaxHeightScrollView(themed);
        scroll.addView(root);
        scroll.setBackground(roundedBackground());
        scroll.setClipToOutline(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        AlertDialog dialog = new AlertDialog.Builder(themed)
                .setView(scroll)
                .create();

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() == null) return;

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int widthPx = Math.min((int) (dm.widthPixels * 0.92f), dp(ctx, MAX_WIDTH_DP));
            int maxHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

            scroll.setMaxHeight(maxHeightPx);

            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        });

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
        tv.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 4));
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
                               String subtitle,
                               boolean initial,
                               @NonNull Callback cb) {
        final boolean[] state = { initial };
        final TextView check = new TextView(ctx);
        check.setText("✓");
        check.setTextSize(18);
        check.setTextColor(0xFF4D6BFE);
        check.setVisibility(initial ? View.VISIBLE : View.INVISIBLE);

        View row = buildRow(ctx, actionId, title, subtitle, cb, false, check);
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
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10));
        row.setMinimumHeight(dp(ctx, 44));
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

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailing != null) {
            row.addView(trailing);
        }

        // Default click → fire action. Toggle overrides after this call.
        row.setOnClickListener(v -> cb.onAction(actionId));
        return row;
    }

    private static View spacer(@NonNull Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 2));
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

    // ------------------------------------------------------------------
    // Max-height ScrollView
    // ------------------------------------------------------------------

    /**
     * ScrollView that will not exceed a caller-supplied maximum height.
     * If the wrapped content is shorter than the cap, it wraps; if it's
     * taller, it caps and scrolls. This is what prevents the dialog from
     * filling the entire viewport on phones.
     */
    private static class MaxHeightScrollView extends ScrollView {

        private int maxHeightPx = Integer.MAX_VALUE;

        MaxHeightScrollView(@NonNull Context context) {
            super(context);
        }

        void setMaxHeight(int px) {
            this.maxHeightPx = px;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int cappedSpec = MeasureSpec.makeMeasureSpec(
                    maxHeightPx, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, cappedSpec);
        }
    }
}
