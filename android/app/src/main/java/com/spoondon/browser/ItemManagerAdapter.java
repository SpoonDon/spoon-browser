package com.spoondon.browser;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
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
 * Holds the full item list as {@code source} and exposes a filtered/sorted
 * view as {@code visible}. Filter and sort are pure client-side operations
 * — the DB is queried once by the dialog and never again until reopen.
 *
 * Rows use {@code activatedBackgroundIndicator} so ListView's built-in
 * CHOICE_MODE_MULTIPLE_MODAL highlights checked rows without needing a
 * Checkable row layout.
 */
public class ItemManagerAdapter extends BaseAdapter {

    public static final int SORT_DATE_DESC = 0;
    public static final int SORT_DATE_ASC  = 1;
    public static final int SORT_TITLE_ASC = 2;
    public static final int SORT_HOST_ASC  = 3;

    public interface OnOverflowClickListener {
        void onOverflow(@NonNull View anchor, int position);
    }

    private final Context context;
    private final int padX;
    private final int padY;
    private final int activatedBgRes;

    private final List<ManagedItem> source  = new ArrayList<>();
    private final List<ManagedItem> visible = new ArrayList<>();

    private String filter = "";
    private int sortMode = SORT_DATE_DESC;

    @Nullable private OnOverflowClickListener overflowListener;

    public ItemManagerAdapter(@NonNull Context context) {
        this.context = context;
        float d = context.getResources().getDisplayMetrics().density;
        this.padX = (int) (16 * d);
        this.padY = (int) (12 * d);

        TypedValue tv = new TypedValue();
        boolean ok = context.getTheme().resolveAttribute(
                android.R.attr.activatedBackgroundIndicator, tv, true);
        this.activatedBgRes = (ok && tv.resourceId != 0) ? tv.resourceId : 0;
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
        String[] needles = filter.isEmpty()
                ? new String[0]
                : filter.split("\\s+");
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
            if (activatedBgRes != 0) row.setBackgroundResource(activatedBgRes);

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(context);
            title.setTextSize(16);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);

            TextView url = new TextView(context);
            url.setTextSize(12);
            url.setAlpha(0.65f);
            url.setMaxLines(1);
            url.setEllipsize(TextUtils.TruncateAt.END);

            col.addView(title);
            col.addView(url);

            TextView overflow = new TextView(context);
            overflow.setText("\u22EE"); // vertical ellipsis
            overflow.setTextSize(22);
            overflow.setPadding(padX, 0, 0, 0);
            overflow.setGravity(Gravity.CENTER);

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
