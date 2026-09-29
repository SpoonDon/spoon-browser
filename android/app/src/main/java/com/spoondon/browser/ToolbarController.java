package com.spoondon.browser;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Owns the top navigation toolbar: address bar, forward / prev-tab / next-tab /
 * new-tab / menu buttons, and the tab counter badge.
 *
 * Extracted from MainActivity (god-object split, slice 2).
 *
 * The controller is UI-only: it never touches WebViews, TabManager, or the
 * database directly. All side-effecting work goes through {@link Callbacks}.
 *
 * Threading: all public methods must be called on the main thread.
 */
public class ToolbarController {

    // ------------------------------------------------------------------------
    // Callbacks
    // ------------------------------------------------------------------------
    public interface Callbacks {
        /** User pressed Enter or tapped a suggestion in the address bar. */
        void onNavigate(@NonNull String input);

        /** Forward button tapped. Controller does not check canGoForward(); do that here. */
        void onForward();

        /** Previous-tab button tapped. */
        void onPreviousTab();

        /** Next-tab button tapped. */
        void onNextTab();

        /** New-tab button tapped. */
        void onNewTab();

        /** Tab counter / badge tapped — user wants the tab switcher. */
        void onShowTabSwitcher();

        /** Menu (hamburger) button tapped. Anchor view is provided for PopupMenu. */
        void onMenuClicked(@NonNull View anchor);

        /**
         * Fetch autocomplete suggestions for the given query.
         * Called on a background thread. Return an empty list (never null) if
         * there is nothing to suggest.
         */
        @NonNull
        List<Suggestion> fetchSuggestions(@NonNull String query);

        /** Executor used to run suggestion fetches off the main thread. */
        @NonNull
        Executor getBackgroundExecutor();
    }

    // ------------------------------------------------------------------------
    // Suggestion value type
    // ------------------------------------------------------------------------
    public static class Suggestion {
        public final String title;
        public final String url;

        public Suggestion(String title, String url) {
            this.title = title;
            this.url = url;
        }

        @Override
        public String toString() {
            return url != null ? url : title;
        }
    }

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------
    private final MainActivity activity;
    private final Callbacks callbacks;
    private final LinearLayout toolbar;

    private AutoCompleteTextView addressBar;
    private SuggestionAdapter addressBarAdapter;

    private Button forwardButton;
    private Button prevTabButton;
    private Button nextTabButton;
    private Button newTabButton;
    private Button menuButton;
    private Button tabBadgeButton;
    private TextView tabIndicator;

    private boolean suppressSuggestions = false;

    // ------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------
    public ToolbarController(@NonNull MainActivity activity, @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
        this.toolbar = buildToolbar();
        wireListeners();
    }

    // ------------------------------------------------------------------------
    // View accessors (for MainActivity and other controllers)
    // ------------------------------------------------------------------------
    @NonNull
    public View getRootView() {
        return toolbar;
    }

    @NonNull
    public AutoCompleteTextView getAddressBar() {
        return addressBar;
    }

    @Nullable
    public Button getMenuButton() {
        return menuButton;
    }

    @Nullable
    public Button getNewTabButton() {
        return newTabButton;
    }

    // ------------------------------------------------------------------------
    // State setters
    // ------------------------------------------------------------------------

    /** Update the address bar text. Passing null or "" clears it. */
    public void setAddress(@Nullable String url) {
        if (addressBar == null) return;
        boolean blank = (url == null || url.isEmpty() || "about:blank".equals(url));
        addressBar.setText(blank ? "" : url);
    }

    /** Currently displayed address-bar text. Never null. */
    @NonNull
    public String getAddress() {
        return addressBar != null && addressBar.getText() != null
                ? addressBar.getText().toString() : "";
    }

    /** Update the tab counter (e.g. "2/5"). */
    public void setTabCounter(int current, int total) {
        String counter = (total <= 0) ? "0/0" : (current + 1) + "/" + total;
        if (tabIndicator != null) tabIndicator.setText(counter);
        if (tabBadgeButton != null) tabBadgeButton.setText(String.valueOf(total));
    }

    /** Switch the address bar into incognito styling. */
    public void setIncognito(boolean incognito) {
        if (addressBar == null) return;
        if (incognito) {
            addressBar.setBackgroundColor(Color.parseColor("#3c1f40"));
            addressBar.setHint("Incognito Search or URL");
        } else {
            addressBar.setBackgroundColor(Color.parseColor("#222222"));
            addressBar.setHint("Search or enter address");
        }
    }

    /** Enable / disable the forward button. On phones the button is hidden anyway. */
    public void setForwardEnabled(boolean enabled) {
        if (forwardButton == null) return;
        if (forwardButton.getVisibility() == View.VISIBLE) {
            forwardButton.setAlpha(enabled ? 1.0f : 0.4f);
        }
    }

    /** Dismiss the autocomplete dropdown. */
    public void hideSuggestions() {
        if (addressBar == null) return;
        try {
            if (addressBar.isAttachedToWindow()) addressBar.dismissDropDown();
        } catch (Exception ignored) {}
    }

    /** Clear focus and hide the soft keyboard. */
    public void clearAddressFocus() {
        if (addressBar == null) return;
        addressBar.clearFocus();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------------------
    // Internal: build the toolbar layout
    // ------------------------------------------------------------------------
    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(Color.parseColor("#141414"));

        forwardButton = makeButton("→");
        forwardButton.setContentDescription("Go Forward");

        prevTabButton = makeButton("◀");
        prevTabButton.setContentDescription("Previous Tab");

        nextTabButton = makeButton("▶");
        nextTabButton.setContentDescription("Next Tab");

        newTabButton = makeButton("+");
        newTabButton.setContentDescription("New Tab");

        menuButton = makeButton("☰");
        menuButton.setContentDescription("Open Menu");

        tabIndicator = new TextView(activity);
        tabIndicator.setTextColor(Color.WHITE);
        tabIndicator.setTextSize(14);
        tabIndicator.setPadding(dp(8), 0, dp(8), 0);

        addressBar = buildAddressBar();

        int screenWidthDp = getScreenWidthDp();

        if (screenWidthDp < 600) {
            // ---- Phone layout ------------------------------------------------
            forwardButton.setVisibility(View.GONE);
            prevTabButton.setVisibility(View.GONE);
            nextTabButton.setVisibility(View.GONE);
            newTabButton.setVisibility(View.GONE);
            tabIndicator.setVisibility(screenWidthDp < 360 ? View.GONE : View.VISIBLE);

            tabBadgeButton = new Button(activity);
            tabBadgeButton.setContentDescription("Open Tab Switcher");
            tabBadgeButton.setText("1");
            tabBadgeButton.setTextColor(Color.WHITE);
            tabBadgeButton.setTextSize(12);
            tabBadgeButton.setTypeface(Typeface.DEFAULT_BOLD);
            tabBadgeButton.setGravity(Gravity.CENTER);
            tabBadgeButton.setPadding(0, 0, 0, 0);
            tabBadgeButton.setIncludeFontPadding(false);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.TRANSPARENT);
            badgeBg.setStroke(dp(2), Color.parseColor("#CCCCCC"));
            badgeBg.setCornerRadius(dp(6));
            tabBadgeButton.setBackground(badgeBg);

            LinearLayout.LayoutParams badgeParams =
                    new LinearLayout.LayoutParams(dp(36), dp(36));
            badgeParams.setMargins(dp(6), 0, dp(6), 0);
            tabBadgeButton.setLayoutParams(badgeParams);

            menuButton.setPadding(0, 0, 0, 0);
            menuButton.setIncludeFontPadding(false);

            bar.addView(addressBar);
            bar.addView(tabBadgeButton);
            bar.addView(menuButton);
        } else {
            // ---- Tablet layout -----------------------------------------------
            bar.addView(forwardButton);
            bar.addView(prevTabButton);
            bar.addView(tabIndicator);
            bar.addView(nextTabButton);
            bar.addView(newTabButton);
            bar.addView(addressBar);
            bar.addView(menuButton);
        }

        return bar;
    }

    private AutoCompleteTextView buildAddressBar() {
        AutoCompleteTextView bar = new AutoCompleteTextView(activity);
        bar.setHint("Search or enter address");
        bar.setTextColor(Color.WHITE);
        bar.setHintTextColor(Color.GRAY);
        bar.setSingleLine(true);
        bar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        bar.setThreshold(1);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#222222"));
        bg.setCornerRadius(dp(20));
        bar.setBackground(bg);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        params.setMargins(dp(8), 0, dp(8), 0);
        bar.setLayoutParams(params);

        addressBarAdapter = new SuggestionAdapter(activity, new ArrayList<>());

        // setAdapter must run after attach; post it to be safe.
        bar.post(() -> {
            try {
                bar.setAdapter(addressBarAdapter);
                bar.setDropDownBackgroundResource(R.drawable.bg_dropdown_rounded);
                bar.setDropDownVerticalOffset(dp(8));
            } catch (Exception ignored) {}
        });

        return bar;
    }

    // ------------------------------------------------------------------------
    // Internal: wire all listeners
    // ------------------------------------------------------------------------
    private void wireListeners() {
        // ---- Editor action (Enter / Go / Search) ----------------------------
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                submitAddress();
                return true;
            }
            return false;
        });

        addressBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                submitAddress();
                return true;
            }
            return false;
        });

        // ---- Focus behaviour: select-all on first focus ---------------------
        final boolean[] justGainedFocus = {false};
        addressBar.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                addressBar.post(() -> {
                    if (addressBar.getText() != null) addressBar.selectAll();
                });
                justGainedFocus[0] = true;
            } else {
                justGainedFocus[0] = false;
            }
        });
        addressBar.setOnClickListener(view -> {
            if (!justGainedFocus[0]) {
                int pos = addressBar.getSelectionStart();
                if (pos >= 0) addressBar.setSelection(pos);
            }
            justGainedFocus[0] = false;
        });

        // ---- Suggestion tap -------------------------------------------------
        addressBar.setOnItemClickListener((parent, view, position, id) -> {
            String rawItem = extractTextFromView(view);
            if (rawItem == null) {
                try {
                    Object o = parent.getItemAtPosition(position);
                    rawItem = o != null ? o.toString() : null;
                } catch (Exception e) {
                    rawItem = addressBar.getText().toString();
                }
            }
            if (rawItem == null || rawItem.isEmpty()) return;

            String cleanUrl = stripLeadingUrl(rawItem);

            suppressSuggestions = true;
            addressBar.setText(cleanUrl);
            addressBar.setSelection(cleanUrl.length());
            try {
                if (addressBar.isAttachedToWindow()) addressBar.dismissDropDown();
            } catch (Exception ignored) {}

            final String finalUrl = cleanUrl;
            addressBar.post(() -> {
                callbacks.onNavigate(finalUrl);
                suppressSuggestions = false;
                clearAddressFocus();
            });
        });

        // ---- Text change: fetch suggestions ---------------------------------
        addressBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressSuggestions) return;
                updateSuggestions(s.toString().trim());
            }
        });

        // ---- Buttons --------------------------------------------------------
        forwardButton.setOnClickListener(v -> callbacks.onForward());
        newTabButton.setOnClickListener(v -> callbacks.onNewTab());
        prevTabButton.setOnClickListener(v -> callbacks.onPreviousTab());
        nextTabButton.setOnClickListener(v -> callbacks.onNextTab());

        if (tabIndicator != null) {
            tabIndicator.setLongClickable(false);
            tabIndicator.setOnLongClickListener(null);
            tabIndicator.setOnClickListener(v -> callbacks.onShowTabSwitcher());
        }
        if (tabBadgeButton != null) {
            tabBadgeButton.setOnClickListener(v -> callbacks.onShowTabSwitcher());
        }
        menuButton.setOnClickListener(v -> callbacks.onMenuClicked(menuButton));
    }

    private void submitAddress() {
        String input = getAddress().trim();
        if (input.isEmpty()) return;
        callbacks.onNavigate(input);
        clearAddressFocus();
    }

    // ------------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------------
    private void updateSuggestions(String query) {
        if (query == null || query.isEmpty()) {
            addressBarAdapter.clear();
            addressBarAdapter.notifyDataSetChanged();
            hideSuggestions();
            return;
        }

        callbacks.getBackgroundExecutor().execute(() -> {
            List<Suggestion> results = callbacks.fetchSuggestions(query);
            if (results == null) results = new ArrayList<>();
            final List<Suggestion> finalResults = results;

            activity.runOnUiThread(() -> {
                addressBarAdapter.clear();
                addressBarAdapter.addAll(finalResults);
                addressBarAdapter.notifyDataSetChanged();

                try {
                    if (addressBar.isAttachedToWindow()) {
                        if (addressBarAdapter.getCount() > 0) {
                            addressBar.showDropDown();
                        } else {
                            addressBar.dismissDropDown();
                        }
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    // ------------------------------------------------------------------------
    // View helpers
    // ------------------------------------------------------------------------
    private Button makeButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#2a2a2a"));
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);

        int size = getToolbarButtonSize();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private int getToolbarButtonSize() {
        int width = getScreenWidthDp();
        if (width < 400) return dp(36);
        if (width < 600) return dp(42);
        return dp(46);
    }

    private int getScreenWidthDp() {
        return activity.getResources().getConfiguration().screenWidthDp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    @Nullable
    private static String extractTextFromView(View view) {
        if (view instanceof TextView) return ((TextView) view).getText().toString();
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child instanceof TextView) {
                    return ((TextView) child).getText().toString();
                }
            }
        }
        return null;
    }

    private static String stripLeadingUrl(String raw) {
        if (raw == null) return "";
        int httpIdx = raw.indexOf("http://");
        int httpsIdx = raw.indexOf("https://");
        int start = -1;
        if (httpIdx != -1 && httpsIdx != -1) start = Math.min(httpIdx, httpsIdx);
        else if (httpIdx != -1) start = httpIdx;
        else if (httpsIdx != -1) start = httpsIdx;
        return start >= 0 ? raw.substring(start).trim() : raw;
    }

    // ------------------------------------------------------------------------
    // Adapter — mirrors the old SuggestionAdapter
    // ------------------------------------------------------------------------
    private static class SuggestionAdapter extends ArrayAdapter<Suggestion> {
        SuggestionAdapter(MainActivity context, List<Suggestion> items) {
            super(context, android.R.layout.simple_list_item_1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView text = (TextView) super.getView(position, convertView, parent);
            Suggestion s = getItem(position);
            if (s != null) text.setText(s.url != null ? s.url : s.title);
            return text;
        }

        @Override
        public Filter getFilter() {
            // Filtering is done by the controller; the adapter is a dumb list.
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence c) {
                    FilterResults r = new FilterResults();
                    r.values = new ArrayList<>();
                    r.count = 0;
                    return r;
                }
                @Override protected void publishResults(CharSequence c, FilterResults r) {
                    notifyDataSetChanged();
                }
            };
        }
    }
}
