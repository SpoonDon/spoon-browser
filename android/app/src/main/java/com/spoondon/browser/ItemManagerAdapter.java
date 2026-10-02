package com.spoondon.browser;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for the unified History + Bookmarks manager.
 *
 * 2026-10-03 (v2, chrome re-skin):
 *   - Rows match the polished menu visual language: 14sp white title,
 *     11sp muted URL, tighter padding (16/11), overflow icon tinted #B8B8B8.
 *   - Activation highlight is now a custom StateListDrawable (subtle accent
 *     tint for the multi-select CAB, soft white for press) instead of the
 *     theme's activatedBackgroundIndicator, which rendered as an opaque
 *     blue stripe that clashed on the dark surface.
 *   - Public API unchanged.
 */
public class ItemManagerAdapter extends BaseAdapter {

    public static final int SORT_DATE_DESC = 0;
    public static final int SORT_DATE_ASC  = 1;
    public static final int SORT_TITLE_ASC = 2;
    public static final int SORT_HOST_ASC  = 3;

    public interface OnOverflowClickListener {
        void onOverflow(@NonNull View anchor, int position);
    }

    // Palette (matches MainMenuDialog / SettingsDialog / ItemManagerDialog v2)
    private static final int COLOR_TEXT_PRIMARY = 0xFFEDEDED;
    private static final int COLOR_TEXT_MUTED   = 0xFF8E8E93;
    private static final int COLOR_ICON         = 0xFFB8B8B8;

    // Activation / press states — subtle enough to read on #1E1E20 surface.
    private static final int COLOR_ACTIVATED = 0x334D6BFE; // accent @ 20%
    private static final int COLOR_PRESSED   = 0x1AFFFFFF; // white @ 10%
    private static final int COLOR_CLEAR     = 0x00000000;

    private static final int ROW_PAD_H_DP = 16;
    private static final int ROW_PAD_V_DP = 11;
    private static final int ICON_DP      = 20;

    private final Context context;
    private final int padX;
    private final int padY;
    private final StateListDrawable rowBackground;

    private final List<ManagedItem> source  = new ArrayList<>();
    private final List<ManagedItem> visible = new ArrayList<>();

    private String filter = "";
    private int sortMode = SORT_DATE_DESC;

    @Nullable private OnOverflowClickListener overflowListener;

    public ItemManagerAdapter(@NonNull Context context) {
        this.context = context;
        float d = context.getResources().getDisplayMetrics().density;
        this.padX = (int) (ROW_PAD_H_DP * d);
        this.padY = (int) (ROW_PAD_V_DP * d);
        this.rowBackground = buildRowBackground();
    }

    /**
     * Custom activation / press state list.
     *
     * ListView's CHOICE_MODE_MULTIPLE_MODAL sets {@code state_activated} on
     * checked rows. The platform's {@code activatedBackgroundIndicator}
     * renders an opaque blue bar that looks wrong on our dark surface, so
     * we define our own: translucent accent for activated, soft white for
     * pressed, transparent otherwise.
     */
    @NonNull
    private static StateListDrawable buildRowBackground() {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{ android.R.attr.state_activated },
                new ColorDrawable(COLOR_ACTIVATED));
        sld.addState(new int[]{ android.R.attr.state_pressed },
                new ColorDrawable(COLOR_PRESSED));
        sld.addState(new int[]{}, new ColorDrawable(COLOR_CLEAR));
        return sld;
    }

    public void setOnOverflowClickListener(@Nullable OnOverflowClickListener l) {
        this.overflowListener = l;
    }

    public void setItems(@NonNull List<ManagedItem> items) {
        source.clear();
        source.addAll(items);
        rebuild();
    }

    public void setFilter(@Nullable String q) {
        this.filter = (q == null) ? "" : q.trim().toLowerCase(Locale.US);
        rebuild();
    }

    public void setSortMode(int mode) {
        this.sortMode = mode;
        rebuild();
    }

    public int getSortMode() { return sortMode; }

    @NonNull
    public List<ManagedItem> getVisibleItems() { return visible; }

    /** Drops the given ids from the source list and rebuilds. */
    public void removeIds(@NonNull List<Long> ids) {
        for (int i = source.size() - 1; i >= 0; i--) {
            if (ids.contains(source.get(i).id)) source.remove(i);
        }
        rebuild();
    }

    /** Re-runs filter + sort. Call after mutating item fields in place. */
    public void refresh() { rebuild(); }

    private void rebuild() {
        visible.clear();
        String[] needles = filter.isEmpty() ? new String[0] : filter.split("\\s+");
        for (ManagedItem it : source) {
            if (needles.length == 0 || it.matches(needles)) visible.add(it);
        }
        Collections.sort(visible, comparator());
        notifyDataSetChanged();
    }

    private Comparator<ManagedItem> comparator() {
        switch (sortMode) {
            case SORT_DATE_ASC:
                return (a, b) -> Long.compare(a.timestamp, b.timestamp);
            case SORT_TITLE_ASC:
                return (a, b) -> a.displayTitle().compareToIgnoreCase(b.displayTitle());
            case SORT_HOST_ASC:
                return (a, b) -> a.displayHost().compareToIgnoreCase(b.displayHost());
            case SORT_DATE_DESC:
            default:
                return (a, b) -> Long.compare(b.timestamp, a.timestamp);
        }
    }

    @Override public int getCount() { return visible.size(); }
    @Override public ManagedItem getItem(int position) { return visible.get(position); }
    @Override public long getItemId(int position) { return visible.get(position).id; }
    @Override public boolean hasStableIds() { return true; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row;
        Holder h;

        if (convertView == null) {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(padX, padY, padX, padY);
            row.setBackground(rowBackground.getConstantState() != null
                    ? (android.graphics.drawable.Drawable) rowBackground.getConstantState().newDrawable()
                    : rowBackground);

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(context);
            title.setTextSize(14);
            title.setTextColor(COLOR_TEXT_PRIMARY);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);

            TextView url = new TextView(context);
            url.setTextSize(11);
            url.setTextColor(COLOR_TEXT_MUTED);
            url.setMaxLines(1);
            url.setEllipsize(TextUtils.TruncateAt.END);
            url.setPadding(0, (int) (2 * context.getResources()
                    .getDisplayMetrics().density), 0, 0);

            col.addView(title);
            col.addView(url);

            TextView overflow = new TextView(context);
            overflow.setText("\u22EE"); // vertical ellipsis
            overflow.setTextSize(20);
            overflow.setTextColor(COLOR_ICON);
            overflow.setPadding(padX, 0, 0, 0);
            overflow.setGravity(Gravity.CENTER);
            overflow.setMinWidth((int) (ICON_DP * context.getResources()
                    .getDisplayMetrics().density));
            overflow.setMinHeight(overflow.getMinHeight());

            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(overflow);

            h = new Holder(title, url, overflow);
            row.setTag(h);
        } else {
            row = (LinearLayout) convertView;
            h = (Holder) row.getTag();
        }

        ManagedItem item = getItem(position);
        h.title.setText(item.displayTitle());
        h.url.setText(item.displayHost());

        final int pos = position;
        h.overflow.setOnClickListener(v -> {
            if (overflowListener != null) overflowListener.onOverflow(v, pos);
        });

        return row;
    }

    private static final class Holder {
        final TextView title;
        final TextView url;
        final TextView overflow;
        Holder(TextView t, TextView u, TextView o) { title = t; url = u; overflow = o; }
    }
}
