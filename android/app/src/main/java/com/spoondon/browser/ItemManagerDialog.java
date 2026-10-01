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

    // ------------------------------------------------------------------
    // List interaction
    // ------------------------------------------------------------------

    @NonNull
    private AbsListView.MultiChoiceModeListener buildCab() {
        return new AbsListView.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position,
                                                  long id, boolean checked) {
                mode.setTitle(listView.getCheckedItemCount() + " selected");
            }

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, MENU_DELETE, 0, "Delete")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(Menu.NONE, MENU_SELECT_ALL, 1, "Select all")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                mode.setTitle("0 selected");
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id == MENU_DELETE) {
                    confirmDeleteSelected(mode);
                    return true;
                }
                if (id == MENU_SELECT_ALL) {
                    for (int i = 0; i < adapter.getCount(); i++) listView.setItemChecked(i, true);
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) { /* no-op */ }
        };
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ManagedItem item = adapter.getItem(position);
        if (item == null) return;
        callbacks.onNavigate(item.getUrl());
        dialog.dismiss();
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private void showSortMenu(View anchor) {
        PopupMenu pm = new PopupMenu(activity, anchor);
        pm.getMenu().add(0, ItemManagerAdapter.SORT_DATE_DESC, 0, "Newest first");
        pm.getMenu().add(0, ItemManagerAdapter.SORT_DATE_ASC,  1, "Oldest first");
        pm.getMenu().add(0, ItemManagerAdapter.SORT_TITLE_ASC, 2, "Title A-Z");
        pm.getMenu().add(0, ItemManagerAdapter.SORT_HOST_ASC,  3, "Host A-Z");
        pm.setOnMenuItemClickListener(mi -> {
            adapter.setSortMode(mi.getItemId());
            return true;
        });
        pm.show();
    }

    private void showItemMenu(View anchor, int position) {
        ManagedItem item = adapter.getItem(position);
        if (item == null) return;

        PopupMenu pm = new PopupMenu(activity, anchor);
        pm.getMenu().add(0, MENU_OPEN,       0, "Open");
        pm.getMenu().add(0, MENU_OPEN_NEW,   1, "Open in new tab");
        pm.getMenu().add(0, MENU_EDIT,       2, "Edit");
        pm.getMenu().add(0, MENU_COPY,       3, "Copy URL");
        pm.getMenu().add(0, MENU_SHARE,      4, "Share");
        pm.getMenu().add(0, MENU_ONE_DELETE, 5, "Delete");

        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case MENU_OPEN:
                    callbacks.onNavigate(item.getUrl());
                    dialog.dismiss();
                    return true;
                case MENU_OPEN_NEW:
                    callbacks.openInNewTab(item.getUrl());
                    return true;
                case MENU_EDIT:
                    editItem(item);
                    return true;
                case MENU_COPY:
                    copyUrl(item);
                    return true;
                case MENU_SHARE:
                    shareUrl(item);
                    return true;
                case MENU_ONE_DELETE:
                    confirmDeleteOne(item);
                    return true;
            }
            return false;
        });
        pm.show();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void editItem(@NonNull ManagedItem item) {
        int pad = dp(16);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText titleIn = new EditText(activity);
        titleIn.setHint("Title");
        titleIn.setSingleLine(true);
        titleIn.setText(item.getTitle() == null ? "" : item.getTitle());

        EditText urlIn = new EditText(activity);
        urlIn.setHint("URL");
        urlIn.setSingleLine(true);
        urlIn.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlIn.setText(item.getUrl());

        box.addView(titleIn);
        box.addView(urlIn);

        new AlertDialog.Builder(activity)
                .setTitle("Edit")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String newTitle = titleIn.getText().toString().trim();
                    String newUrl = urlIn.getText().toString().trim();
                    if (newUrl.isEmpty()) {
                        Toast.makeText(activity, "URL cannot be empty",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    source.update(item,
                            newTitle.isEmpty() ? null : newTitle,
                            newUrl);
                    item.setTitle(newTitle.isEmpty() ? null : newTitle);
                    item.setUrl(newUrl);
                    adapter.refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyUrl(@NonNull ManagedItem item) {
        ClipboardManager cm = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("url", item.getUrl()));
            Toast.makeText(activity, "URL copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareUrl(@NonNull ManagedItem item) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, item.getUrl());
        activity.startActivity(Intent.createChooser(i, "Share via"));
    }

    // ------------------------------------------------------------------
    // Delete flows
    // ------------------------------------------------------------------

    private void confirmDeleteOne(@NonNull ManagedItem item) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete")
                .setMessage("Delete \"" + item.displayTitle() + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    List<Long> ids = new ArrayList<>(1);
                    ids.add(item.id);
                    source.delete(ids);
                    adapter.removeIds(ids);
                    updateEmpty();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteSelected(@NonNull ActionMode mode) {
        final List<Long> ids = new ArrayList<>();
        android.util.SparseBooleanArray checked = listView.getCheckedItemPositions();
        if (checked != null) {
            for (int i = 0; i < checked.size(); i++) {
                if (checked.valueAt(i)) {
                    ManagedItem it = adapter.getItem(checked.keyAt(i));
                    if (it != null) ids.add(it.id);
                }
            }
        }
        if (ids.isEmpty()) {
            mode.finish();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Delete " + ids.size() + " item(s)?")
                .setPositiveButton("Delete", (d, w) -> {
                    source.delete(ids);
                    adapter.removeIds(ids);
                    mode.finish();
                    updateEmpty();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(activity)
                .setTitle("Clear all?")
                .setMessage("This removes every entry. Cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    source.clearAll();
                    adapter.setItems(new ArrayList<>());
                    updateEmpty();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void updateEmpty() {
        boolean empty = adapter.getCount() == 0;
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
