package com.spoondon.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
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
 * 2026-10-03 (v2, chrome re-skin):
 *   - Dialog shell now matches the polished menu visual language: custom
 *     Dialog with rounded #1E1E20 surface, top-right anchor, header row
 *     with title + close, styled search field, dividers, empty state,
 *     footer "Clear all" row.
 *   - Architecture unchanged: ListView + CHOICE_MODE_MULTIPLE_MODAL + CAB.
 *   - Same Callbacks / DataSource contracts. No caller changes.
 */
public final class ItemManagerDialog {

    public interface Callbacks {
        void onNavigate(@NonNull String url);
        void openInNewTab(@NonNull String url);
        void onSaveAsBookmark(@NonNull ManagedItem item);
    }

    public interface DataSource {
        @NonNull List<ManagedItem> load();
        void update(@NonNull ManagedItem item, @Nullable String newTitle, @NonNull String newUrl);
        void delete(@NonNull List<Long> ids);
        void clearAll();
    }

    private static final int MENU_DELETE        = 1;
    private static final int MENU_SELECT_ALL    = 2;
    private static final int MENU_OPEN          = 10;
    private static final int MENU_OPEN_NEW      = 11;
    private static final int MENU_EDIT          = 12;
    private static final int MENU_COPY          = 13;
    private static final int MENU_SHARE         = 14;
    private static final int MENU_ONE_DELETE    = 15;
    private static final int MENU_SAVE_BOOKMARK = 16;

    private static final int COLOR_SURFACE      = 0xFF1E1E20;
    private static final int COLOR_TEXT_PRIMARY = 0xFFEDEDED;
    private static final int COLOR_TEXT_MUTED   = 0xFF8E8E93;
    private static final int COLOR_ICON         = 0xFFB8B8B8;
    private static final int COLOR_DIVIDER      = 0xFF2C2C2E;
    private static final int COLOR_DANGER       = 0xFFFF5A4D;

    private static final int PAD_H_DP = 16;
    private static final int PAD_V_DP = 11;

    private final Activity activity;
    private final DataSource source;
    private final Callbacks callbacks;
    private final ItemManagerAdapter adapter;
    private final ListView listView;
    private final TextView emptyView;
    private Dialog dialog;

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

        Context themed = new ContextThemeWrapper(activity, R.style.SpoonMenuDialog);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedBackground());

        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(PAD_H_DP), dp(14), dp(PAD_H_DP), dp(6));

        TextView titleView = new TextView(themed);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTextColor(COLOR_TEXT_PRIMARY);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(themed);
        closeBtn.setText("\u2715");
        closeBtn.setTextSize(16);
        closeBtn.setTextColor(COLOR_ICON);
        closeBtn.setPadding(dp(8), dp(8), dp(4), dp(8));
        closeBtn.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
        });
        header.addView(closeBtn);

        LinearLayout searchRow = new LinearLayout(themed);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(PAD_H_DP), dp(4), dp(PAD_H_DP), dp(10));

        EditText search = new EditText(themed);
        search.setHint("Search title, URL, or host");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setTextColor(COLOR_TEXT_PRIMARY);
        search.setHintTextColor(COLOR_TEXT_MUTED);
        search.setBackgroundResource(searchFieldBackground());
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.setFilter(s.toString());
                updateEmpty();
            }
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sortBtn = new TextView(themed);
        sortBtn.setText("Sort \u25BE");
        sortBtn.setTextSize(13);
        sortBtn.setTextColor(COLOR_ICON);
        sortBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
        sortBtn.setOnClickListener(this::showSortMenu);
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sortLp.setMarginStart(dp(8));
        searchRow.addView(sortBtn, sortLp);

        listView = new ListView(themed);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        listView.setMultiChoiceModeListener(buildCab());
        listView.setOnItemClickListener(this::onItemClick);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setDivider(new ColorDrawable(COLOR_DIVIDER));
        listView.setDividerHeight(dp(1));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setVerticalScrollBarEnabled(false);

        emptyView = new TextView(themed);
        emptyView.setText("No matches");
        emptyView.setTextSize(13);
        emptyView.setTextColor(COLOR_TEXT_MUTED);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);

        View divider = new View(themed);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));

        TextView clearAll = new TextView(themed);
        clearAll.setText("Clear all");
        clearAll.setTextSize(14);
        clearAll.setTextColor(COLOR_DANGER);
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setPadding(dp(PAD_H_DP), dp(14), dp(PAD_H_DP), dp(14));
        clearAll.setBackgroundResource(selectableItemBackground(themed));
        clearAll.setClickable(true);
        clearAll.setFocusable(true);
        clearAll.setOnClickListener(v -> confirmClearAll());

        root.addView(header);
        root.addView(searchRow);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(divider, divLp);
        root.addView(clearAll);

        adapter.setOnOverflowClickListener(this::showItemMenu);

        dialog = new Dialog(themed);
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            View decor = w.getDecorView();
            if (decor != null) {
                decor.setMinimumWidth(0);
                decor.setMinimumHeight(0);
            }
        }

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
            w.setLayout((int) (dm.widthPixels * 0.92f),
                        (int) (dm.heightPixels * 0.78f));
        }
    }

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
                if (id == MENU_DELETE) { confirmDeleteSelected(mode); return true; }
                if (id == MENU_SELECT_ALL) {
                    for (int i = 0; i < adapter.getCount(); i++) listView.setItemChecked(i, true);
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) { }
        };
    }

    private void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ManagedItem item = adapter.getItem(position);
        if (item == null) return;
        callbacks.onNavigate(item.getUrl());
        dialog.dismiss();
    }

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

        if (item.type == ManagedItem.TYPE_HISTORY) {
            pm.getMenu().add(0, MENU_SAVE_BOOKMARK, 5, "Save as Bookmark");
            pm.getMenu().add(0, MENU_ONE_DELETE,    6, "Delete");
        } else {
            pm.getMenu().add(0, MENU_ONE_DELETE,    5, "Delete");
        }

        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case MENU_OPEN:          callbacks.onNavigate(item.getUrl()); dialog.dismiss(); return true;
                case MENU_OPEN_NEW:      callbacks.openInNewTab(item.getUrl()); return true;
                case MENU_EDIT:          editItem(item); return true;
                case MENU_COPY:          copyUrl(item); return true;
                case MENU_SHARE:         shareUrl(item); return true;
                case MENU_SAVE_BOOKMARK: callbacks.onSaveAsBookmark(item); return true;
                case MENU_ONE_DELETE:    confirmDeleteOne(item); return true;
            }
            return false;
        });
        pm.show();
    }

    private void editItem(@NonNull ManagedItem item) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

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
                            newTitle.isEmpty() ? null : newTitle, newUrl);
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
        if (ids.isEmpty()) { mode.finish(); return; }
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

    private void updateEmpty() {
        boolean empty = adapter.getCount() == 0;
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private GradientDrawable roundedBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_SURFACE);
        g.setCornerRadius(28f);
        return g;
    }

    private int searchFieldBackground() {
        TypedValue out = new TypedValue();
        activity.getTheme().resolveAttribute(
                android.R.attr.editTextBackground, out, true);
        return out.resourceId;
    }

    private int selectableItemBackground(Context ctx) {
        TypedValue out = new TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
