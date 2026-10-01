package com.spoondon.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified History + Bookmarks manager.
 *
 * One dialog, two modes. Search bar filters client-side across title, URL,
 * and host. Sort menu offers newest / oldest / title A-Z / host A-Z.
 * Long-press enters ListView's built-in multi-select CAB (Delete / Select
 * all). The per-row overflow exposes single-item actions (Open, Open in
 * new tab, Edit, Copy URL, Share, Delete). "Clear all" is on the dialog's
 * neutral button with a confirmation.
 *
 * The dialog never touches SQLite directly — it calls back into a
 * {@link DataSource} so HistoryController and BookmarkManager each supply
 * their own persistence.
 */
public final class ItemManagerDialog {

    public interface Callbacks {
        void onNavigate(@NonNull String url);
        void openInNewTab(@NonNull String url);
    }

    public interface DataSource {
        @NonNull List<ManagedItem> load();
        void update(@NonNull ManagedItem item, @Nullable String newTitle, @NonNull String newUrl);
        void delete(@NonNull List<Long> ids);
        void clearAll();
    }

    private static final int MENU_DELETE     = 1;
    private static final int MENU_SELECT_ALL = 2;
    private static final int MENU_OPEN       = 10;
    private static final int MENU_OPEN_NEW   = 11;
    private static final int MENU_EDIT       = 12;
    private static final int MENU_COPY       = 13;
    private static final int MENU_SHARE      = 14;
    private static final int MENU_ONE_DELETE = 15;

    private final Activity activity;
    private final DataSource source;
    private final Callbacks callbacks;
    private final ItemManagerAdapter adapter;
    private final ListView listView;
    private final TextView emptyView;
    private final AlertDialog dialog;

    private ItemManagerDialog(@NonNull Activity activity,
                              @NonNull String title,
                              @NonNull DataSource source,
                              @NonNull Callbacks callbacks,
                              @NonNull List<ManagedItem> items) {
        this.activity = activity;
        this.source = source;
        this.callbacks = callbacks;
        this.adapter = new ItemManagerAdapter(activity);
        this.adapter.setItems(items);

        int pad = dp(12);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // --- header: search field + sort button ---
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(pad, pad, pad, pad);

        EditText search = new EditText(activity);
        search.setHint("Search title, URL, or host");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.setFilter(s.toString());
                updateEmpty();
            }
        });

        TextView sortBtn = new TextView(activity);
        sortBtn.setText("Sort \u25BE");
        sortBtn.setTextSize(14);
        sortBtn.setPadding(pad, pad, pad, pad);
        sortBtn.setOnClickListener(this::showSortMenu);

        header.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(sortBtn);

        // --- list ---
        listView = new ListView(activity);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        listView.setMultiChoiceModeListener(buildCab());
        listView.setOnItemClickListener(this::onItemClick);

        // --- empty state ---
        emptyView = new TextView(activity);
        emptyView.setText("No matches");
        emptyView.setPadding(pad, pad * 3, pad, pad);
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setVisibility(View.GONE);

        root.addView(header);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        adapter.setOnOverflowClickListener(this::showItemMenu);

        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("Close", null)
                .setNeutralButton("Clear all", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> confirmClearAll());
        });

        updateEmpty();
    }

    public static void show(@NonNull Activity activity,
                            @NonNull String title,
                            @NonNull DataSource source,
                            @NonNull Callbacks callbacks) {
        List<ManagedItem> items = source.load();
        if (items.isEmpty()) {
            Toast.makeText(activity, "Nothing to show", Toast.LENGTH_SHORT).show();
            return;
        }
        new ItemManagerDialog(activity, title, source, callbacks, items).show();
    }

    private void show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            w.setLayout((int) (dm.widthPixels * 0.95f), (int) (dm.heightPixels * 0.80f));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    // === PART 2 CONTINUES HERE ===
}
