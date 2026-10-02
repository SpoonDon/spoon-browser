package com.spoondon.browser;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
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

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Shared polished dialog builders matching FilterListsDialog / MainMenuDialog
 * visual language. Replaces the raw AlertDialog.Builder chrome that the
 * AdBlock sub-dialogs used before 2026-10-03.
 *
 * Visual contract (all dialogs share):
 *   - Rounded #1E1E20 surface, 28f corner radius
 *   - Anchored TOP|END (side 8dp, top 60dp)
 *   - Header: bold 17sp title, optional close glyph
 *   - Rows: 20dp icon + 16dp gap + 14sp title / 11sp subtitle, padding 16/11
 *   - Divider #2C2C2E, danger rows #FF5A4D, checkmark #4D6BFE
 *   - Two-pass measure -> dialog hugs widest row
 *   - MaxSizeScrollView caps 88% width / 80% height
 *
 * Public API:
 *   list()          title + clickable rows + optional footer
 *   input()         title + message + EditText + Save/Cancel
 *   message()       title + message + positive/negative buttons
 *   confirm()       same as message but positive is danger-tinted
 *   singleChoice()  title + radio list, tap = pick + dismiss
 */
public final class SpoonDialog {

    // ---- shared colours ----
    private static final int COLOR_SURFACE      = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY = 0xFFEDEDED;
    private static final int COLOR_TEXT_MUTED   = 0xFF8E8E93;
    private static final int COLOR_ICON         = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER      = 0xFF2C2C2E;
    private static final int COLOR_DANGER       = 0xFFFF5A4D;
    private static final int COLOR_ACCENT       = 0xFF4D6BFE;

    // ---- shared layout ----
    private static final float MAX_WIDTH_FRACTION  = 0.88f;
    private static final float MAX_HEIGHT_FRACTION = 0.80f;
    private static final int   TOP_MARGIN_DP       = 60;
    private static final int   SIDE_MARGIN_DP      = 8;

    private static final int ROW_PAD_H_DP = 16;
    private static final int ROW_PAD_V_DP = 11;
    private static final int ICON_SIZE_DP = 20;
    private static final int ICON_GAP_DP  = 16;

    private static final String TAG_DIVIDER = "spoon_dialog_divider";

    private SpoonDialog() {}

    // ========================================================================
    // Model + listeners
    // ========================================================================

    /** One row in a list() or singleChoice() dialog. */
    public static final class Item {
        public final String title;
        public final String subtitle;       // nullable
        @DrawableRes public final int iconRes;  // 0 = no icon
        public final boolean checked;
        public final boolean danger;

        public Item(@NonNull String title,
                    @Nullable String subtitle,
                    @DrawableRes int iconRes,
                    boolean checked,
                    boolean danger) {
            this.title = title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.checked = checked;
            this.danger = danger;
        }
    }

    public interface RowClick     { void onClick(int position); }
    public interface RowLongClick { void onLongClick(int position, @NonNull View anchor); }
    public interface InputCallback { void onInput(@NonNull String text); }
    public interface ChoiceCallback { void onChoice(int index); }

    // ========================================================================
    // Public builders
    // ========================================================================

    public static void list(@NonNull Context ctx,
                            @NonNull String title,
                            @NonNull List<Item> items,
                            @NonNull RowClick click) {
        list(ctx, title, items, click, null, null, null);
    }

    public static void list(@NonNull Context ctx,
                            @NonNull String title,
                            @NonNull List<Item> items,
                            @NonNull RowClick click,
                            @Nullable String footerLabel,
                            @Nullable Runnable footerAction) {
        list(ctx, title, items, click, null, footerLabel, footerAction);
    }

    public static void list(@NonNull Context ctx,
                            @NonNull String title,
                            @NonNull List<Item> items,
                            @NonNull RowClick click,
                            @Nullable RowLongClick longClick,
                            @Nullable String footerLabel,
                            @Nullable Runnable footerAction) {
        Shell s = buildShell(ctx, title, true);
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            View row = itemRow(ctx, items.get(i), v -> {
                s.dialog.dismiss();
                click.onClick(idx);
            });
            if (longClick != null) {
                row.setOnLongClickListener(v -> {
                    longClick.onLongClick(idx, v);
                    return true;
                });
            }
            s.body.addView(row);
            if (i < items.size() - 1) s.body.addView(divider(ctx));
        }
        if (footerLabel != null && footerAction != null) {
            s.body.addView(divider(ctx));
            s.body.addView(footerRow(ctx, footerLabel, v -> {
                s.dialog.dismiss();
                footerAction.run();
            }));
        }
        show(ctx, s);
    }

    public static void input(@NonNull Context ctx,
                             @NonNull String title,
                             @Nullable String message,
                             @Nullable String hint,
                             @Nullable String prefill,
                             @NonNull String saveLabel,
                             @NonNull InputCallback onSave) {
        Shell s = buildShell(ctx, title, true);
        if (message != null && !message.isEmpty()) {
            s.body.addView(smallMuted(ctx, message));
        }
        EditText input = new EditText(s.themed);
        input.setSingleLine(true);
        input.setText(prefill == null ? "" : prefill);
        if (hint != null) input.setHint(hint);
        input.setTextColor(COLOR_TEXT_PRIMARY);
        input.setHintTextColor(COLOR_TEXT_MUTED);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin   = dp(ctx, ROW_PAD_H_DP);
        ilp.rightMargin  = dp(ctx, ROW_PAD_H_DP);
        ilp.topMargin    = dp(ctx, 4);
        ilp.bottomMargin = dp(ctx, 8);
        input.setLayoutParams(ilp);
        s.body.addView(input);

        s.body.addView(divider(ctx));
        addButtonRow(ctx, s, saveLabel, "Cancel",
                v -> {
                    String txt = input.getText().toString().trim();
                    s.dialog.dismiss();
                    onSave.onInput(txt);
                },
                v -> s.dialog.dismiss(),
                false);
        show(ctx, s);
        input.requestFocus();
    }

    public static void message(@NonNull Context ctx,
                               @NonNull String title,
                               @NonNull String message,
                               @NonNull String positiveLabel,
                               @Nullable String negativeLabel,
                               @NonNull Runnable onPositive) {
        buttonsInternal(ctx, title, message, positiveLabel, negativeLabel,
                onPositive, false);
    }

    public static void confirm(@NonNull Context ctx,
                               @NonNull String title,
                               @NonNull String message,
                               @NonNull String positiveLabel,
                               @Nullable String negativeLabel,
                               @NonNull Runnable onPositive) {
        buttonsInternal(ctx, title, message, positiveLabel, negativeLabel,
                onPositive, true);
    }

    public static void singleChoice(@NonNull Context ctx,
                                    @NonNull String title,
                                    @NonNull String[] options,
                                    int selectedIdx,
                                    @NonNull ChoiceCallback cb) {
        Shell s = buildShell(ctx, title, true);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            Item it = new Item(options[i], null, 0, i == selectedIdx, false);
            s.body.addView(itemRow(ctx, it, v -> {
                s.dialog.dismiss();
                cb.onChoice(idx);
            }));
            if (i < options.length - 1) s.body.addView(divider(ctx));
        }
        show(ctx, s);
    }

    // ========================================================================
    // Shell + helpers
    // ========================================================================

    private static final class Shell {
        final Context themed;
        final Dialog dialog;
        final LinearLayout body;   // rows go here
        Shell(Context themed, Dialog dialog, LinearLayout body) {
            this.themed = themed;
            this.dialog = dialog;
            this.body = body;
        }
    }

    private static Shell buildShell(@NonNull Context ctx,
                                    @NonNull String title,
                                    boolean withClose) {
        Context themed = new ContextThemeWrapper(ctx, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());
        root.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));

        // header row
        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, 12),
                          dp(ctx, ROW_PAD_H_DP), dp(ctx, 6));

        TextView titleView = new TextView(themed);
        titleView.setText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(COLOR_TEXT_PRIMARY);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Dialog dialog = new Dialog(themed);
        if (withClose) {
            TextView closeBtn = new TextView(themed);
            closeBtn.setText("\u2715");
            closeBtn.setTextSize(15);
            closeBtn.setTextColor(COLOR_ICON);
            closeBtn.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 2), dp(ctx, 6));
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            header.addView(closeBtn);
        }

        LinearLayout body = new LinearLayout(themed);
        body.setOrientation(LinearLayout.VERTICAL);

        root.addView(header);
        root.addView(body);

        dialog.setContentView(root);

        // tag root as the shell root so show() can find it
        root.setTag(R.id.spoon_dialog_root, root);
        return new Shell(themed, dialog, body);
    }

    /**
     * Confirm / message dialogs. Builds shell + message TextView + button row.
     */
    private static void buttonsInternal(@NonNull Context ctx,
                                        @NonNull String title,
                                        @NonNull String message,
                                        @NonNull String positiveLabel,
                                        @Nullable String negativeLabel,
                                        @NonNull Runnable onPositive,
                                        boolean danger) {
        Shell s = buildShell(ctx, title, true);
        s.body.addView(smallMuted(ctx, message));
        s.body.addView(divider(ctx));
        addButtonRow(ctx, s, positiveLabel,
                negativeLabel == null ? null : negativeLabel,
                v -> { s.dialog.dismiss(); onPositive.run(); },
                v -> s.dialog.dismiss(),
                danger);
        show(ctx, s);
    }

    private static void addButtonRow(@NonNull Context ctx,
                                     @NonNull Shell s,
                                     @NonNull String positiveLabel,
                                     @Nullable String negativeLabel,
                                     @NonNull View.OnClickListener onPositive,
                                     @NonNull View.OnClickListener onNegative,
                                     boolean danger) {
        LinearLayout row = new LinearLayout(s.themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));

        if (negativeLabel != null) {
            TextView neg = buttonLabel(ctx, negativeLabel, COLOR_ICON);
            neg.setOnClickListener(onNegative);
            row.addView(neg);
        }
        TextView pos = buttonLabel(ctx, positiveLabel,
                danger ? COLOR_DANGER : COLOR_ACCENT);
        pos.setOnClickListener(onPositive);
        row.addView(pos);

        s.body.addView(row);
    }

    @NonNull
    private static TextView buttonLabel(@NonNull Context ctx,
                                        @NonNull String text,
                                        int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        tv.setBackgroundResource(selectableItemBackground(ctx));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    /** One row: [icon] [title + subtitle] [checkmark?] */
    @NonNull
    private static View itemRow(@NonNull Context ctx,
                                @NonNull Item item,
                                @NonNull View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP),
                       dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP));
        row.setBackgroundResource(selectableItemBackground(ctx));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);

        if (item.iconRes != 0) {
            ImageView icon = new ImageView(ctx);
            icon.setImageResource(item.iconRes);
            icon.setColorFilter(item.danger ? COLOR_DANGER : COLOR_ICON);
            row.addView(icon, new LinearLayout.LayoutParams(
                    dp(ctx, ICON_SIZE_DP), dp(ctx, ICON_SIZE_DP)));
        }

        // title + optional subtitle column
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (item.iconRes != 0) colLp.setMarginStart(dp(ctx, ICON_GAP_DP));
        row.addView(col, colLp);

        TextView title = new TextView(ctx);
        title.setText(item.title);
        title.setTextSize(14);
        title.setTextColor(item.danger ? COLOR_DANGER : COLOR_TEXT_PRIMARY);
        title.setSingleLine(item.subtitle == null || item.subtitle.isEmpty());
        col.addView(title);

        if (item.subtitle != null && !item.subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(item.subtitle);
            sub.setTextSize(11);
            sub.setTextColor(COLOR_TEXT_MUTED);
            sub.setSingleLine(true);
            col.addView(sub);
        }

        // reserved checkmark slot (24dp)
        TextView check = new TextView(ctx);
        check.setText("\u2713");
        check.setTextSize(16);
        check.setTextColor(COLOR_ACCENT);
        check.setVisibility(item.checked ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams ckLp = new LinearLayout.LayoutParams(
                dp(ctx, 24), ViewGroup.LayoutParams.WRAP_CONTENT);
        ckLp.setMarginStart(dp(ctx, 8));
        row.addView(check, ckLp);

        return row;
    }

    /** Non-clickable footer row (e.g. "Clear all" or "Add host"). */
    @NonNull
    private static View footerRow(@NonNull Context ctx,
                                  @NonNull String label,
                                  @NonNull View.OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(COLOR_DANGER);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, 14),
                      dp(ctx, ROW_PAD_H_DP), dp(ctx, 14));
        tv.setBackgroundResource(selectableItemBackground(ctx));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(click);
        return tv;
    }

    @NonNull
    private static TextView smallMuted(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(COLOR_TEXT_MUTED);
        tv.setLineSpacing(0, 1.15f);
        tv.setPadding(dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP),
                      dp(ctx, ROW_PAD_H_DP), dp(ctx, ROW_PAD_V_DP));
        return tv;
    }

    @NonNull
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

    // ========================================================================
    // Show: two-pass measure + anchored TOP|END + capped scroll
    // ========================================================================

    private static void show(@NonNull Context ctx, @NonNull Shell s) {
        // Shell.buildShell put the body inside a root — retrieve it.
        LinearLayout root = (LinearLayout) s.body.getParent();

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int capWidthPx  = (int) (dm.widthPixels  * MAX_WIDTH_FRACTION);
        int capHeightPx = (int) (dm.heightPixels * MAX_HEIGHT_FRACTION);

        // Pass 1: natural width — rows WRAP_CONTENT, dividers zero.
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

        // Pass 2: pin every child to natural width.
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null) {
                lp.width = contentWidth;
                child.setLayoutParams(lp);
            }
        }

        // Wrap in capped scroll so tall dialogs scroll instead of eating the screen.
        MaxSizeScrollView scroll = new MaxSizeScrollView(s.themed);
        scroll.setBackground(roundedBackground());
        scroll.setClipToOutline(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setMaxWidth(capWidthPx);
        scroll.setMaxHeight(capHeightPx);

        // Detach root from dialog (it was set via setContentView) and reattach to scroll.
        scroll.addView(root,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        s.dialog.setContentView(scroll);

        Window w = s.dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            WindowManager.LayoutParams params = w.getAttributes();
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = dp(ctx, SIDE_MARGIN_DP);
            params.y = dp(ctx, TOP_MARGIN_DP);
            w.setAttributes(params);

            View decor = w.getDecorView();
            if (decor != null) {
                decor.setMinimumWidth(0);
                decor.setMinimumHeight(0);
            }
        }

        s.dialog.show();
    }

    private static class MaxSizeScrollView extends ScrollView {
        private int maxWidthPx  = Integer.MAX_VALUE;
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxSizeScrollView(Context context) { super(context); }
        void setMaxWidth(int px)  { this.maxWidthPx = px;  requestLayout(); }
        void setMaxHeight(int px) { this.maxHeightPx = px; requestLayout(); }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    capSpec(widthMeasureSpec,  maxWidthPx),
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
