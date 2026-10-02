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
 * Main filter-lists menu. Same visual language as MainMenuDialog /
 * SettingsDialog. Actions delegate to AdBlockController.
 *
 * Sub-dialogs (preset catalog, manage subscriptions, etc.) remain their
 * existing AlertDialogs — out of scope for this re-skin.
 */
public class FilterListsDialog {

    public interface Callback {
        void onAction(@NonNull String actionId);
    }

    public static final String ACTION_PRESET_CATALOG = "fl_preset";
    public static final String ACTION_MANAGE_SUBS    = "fl_manage";
    public static final String ACTION_ADD_CUSTOM     = "fl_add_custom";
    public static final String ACTION_IMPORT_CLIP    = "fl_import";
    public static final String ACTION_UPDATE_ALL     = "fl_update_all";
    public static final String ACTION_AUTO_UPDATE    = "fl_auto_update";
    public static final String ACTION_SITE_ALLOWLIST = "fl_allowlist";
    public static final String ACTION_CLEAR_ALL      = "fl_clear_all";

    private static final float MAX_WIDTH_FRACTION  = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.85f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int COLOR_SURFACE      = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY = 0xFFEDEDED;
    private static final int COLOR_TEXT_MUTED   = 0xFF8E8E93;
    private static final int COLOR_ICON         = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER      = 0xFF2C2C2E;
    private static final int COLOR_DANGER       = 0xFFFF5A4D;

    private static final int ROW_PAD_H_DP = 16;
    private static final int ROW_PAD_V_DP = 11;
    private static final int ICON_SIZE_DP = 20;
    private static final int ICON_GAP_DP  = 16;

    private static final String TAG_DIVIDER = "spoon_filterlists_divider";

    public static void show(@NonNull Context ctx,
                            @NonNull String summaryText,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(0, dp(themed, 4), 0, dp(themed, 6));

        TextView summary = new TextView(themed);
        summary.setText(summaryText);
        summary.setTextSize(12);
        summary.setTextColor(COLOR_TEXT_MUTED);
        summary.setLineSpacing(0, 1.15f);
        summary.setPadding(dp(themed, ROW_PAD_H_DP), dp(themed, ROW_PAD_V_DP),
                           dp(themed, ROW_PAD_H_DP), dp(themed, ROW_PAD_V_DP));
        root.addView(summary);
        root.addView(divider(themed));

        root.addView(row(themed, ACTION_PRESET_CATALOG, R.drawable.ic_menu_bookmark,
                "Preset catalog", cb));
        root.addView(row(themed, ACTION_MANAGE_SUBS, R.drawable.ic_menu_settings,
                "Manage subscriptions", cb));
        root.addView(row(themed, ACTION_ADD_CUSTOM, R.drawable.ic_settings_import,
                "Add custom URL", cb));
        root.addView(row(themed, ACTION_IMPORT_CLIP, R.drawable.ic_settings_import,
                "Import from clipboard", cb));
        root.addView(divider(themed));

        root.addView(row(themed, ACTION_UPDATE_ALL, R.drawable.ic_menu_reload,
                "Update all subscriptions", cb));
        root.addView(row(themed, ACTION_AUTO_UPDATE, R.drawable.ic_settings_animation,
                "Auto-update interval", cb));
        root.addView(divider(themed));

        root.addView(row(themed, ACTION_SITE_ALLOWLIST, R.drawable.ic_menu_shield,
                "Site allowlist", cb));
        root.addView(dangerRow(themed, ACTION_CLEAR_ALL, R.drawable.ic_menu_exit,
                "Clear all lists", cb));

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

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

    private static View row(Context ctx, String actionId,
                            @DrawableRes int iconRes, String title,
                            Callback cb) {
        return buildRow(ctx, actionId, iconRes, title, cb, false);
    }

    private static View dangerRow(Context ctx, String actionId,
                                  @DrawableRes int iconRes, String title,
                                  Callback cb) {
        return buildRow(ctx, actionId, iconRes, title, cb, true);
    }

    private static View buildRow(Context ctx, String actionId,
                                 @DrawableRes int iconRes, String title,
                                 Callback cb, boolean danger) {
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
        icon.setColorFilter(danger ? COLOR_DANGER : COLOR_ICON);
        row.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, ICON_SIZE_DP), dp(ctx, ICON_SIZE_DP)));

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(danger ? COLOR_DANGER : COLOR_TEXT_PRIMARY);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.setMarginStart(dp(ctx, ICON_GAP_DP));
        row.addView(label, labelLp);

        row.setOnClickListener(v -> cb.onAction(actionId));
        return row;
    }

    private static View divider(Context ctx) {
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

    private static int selectableItemBackground(Context ctx) {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, ctx.getResources().getDisplayMetrics());
    }

    private static class MaxSizeScrollView extends ScrollView {
        private int maxWidthPx  = Integer.MAX_VALUE;
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxSizeScrollView(Context context) { super(context); }
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
