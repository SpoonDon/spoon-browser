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
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * Search engine picker. Same visual language as {@link MainMenuDialog} and
 * {@link SettingsDialog}: top-right anchor, two-pass measure, icons, check
 * on the currently selected engine. Tap a row -> selects, dismisses, toasts.
 *
 * One-shot dialog: opening again re-reads the current engine from prefs.
 */
public class SearchEngineDialog {

    public interface Callback {
        void onEngineSelected(@NonNull String engineValue);
    }

    // Single source of truth. Extend here to add more.
    private static final String[] VALUES = { "brave",  "google", "duckduckgo" };
    private static final String[] LABELS = { "Brave",  "Google", "DuckDuckGo" };

    private static final float MAX_WIDTH_FRACTION  = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.85f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int COLOR_SURFACE       = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY  = 0xFFEDEDED;
    private static final int COLOR_ICON          = 0xFFB8B8B8;
    private static final int COLOR_ACCENT        = 0xFF4D6BFE;

    private static final int ROW_PAD_H_DP   = 16;
    private static final int ROW_PAD_V_DP   = 11;
    private static final int ICON_SIZE_DP   = 20;
    private static final int ICON_GAP_DP    = 16;

    public static void show(@NonNull Context ctx,
                            @NonNull String currentEngine,
                            @NonNull Callback cb) {

        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(0, dp(themed, 6), 0, dp(themed, 6));

        // Holder so row click handlers can dismiss this dialog.
        final Dialog[] dialogHolder = new Dialog[1];

        for (int i = 0; i < VALUES.length; i++) {
            final String value = VALUES[i];
            final String label = LABELS[i];
            boolean selected = value.equals(currentEngine);

            final TextView check = new TextView(themed);
            check.setText("\u2713");
            check.setTextSize(18);
            check.setTextColor(COLOR_ACCENT);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);

            View row = buildRow(themed, R.drawable.ic_menu_search, label, check);
            row.setOnClickListener(v -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                Toast.makeText(ctx, label + " set as default",
                        Toast.LENGTH_SHORT).show();
                cb.onEngineSelected(value);
            });
            root.addView(row);
        }

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).width =
                        ViewGroup.LayoutParams.WRAP_CONTENT;
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
        dialogHolder[0] = dialog;
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

    private static View buildRow(@NonNull Context ctx,
                                 int iconRes,
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
