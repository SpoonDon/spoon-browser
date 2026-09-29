package com.spoondon.browser;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * All vault-related UI: the per-site credential picker, the "Saved Passwords"
 * manager, and the CredentialAdapter row layout.
 *
 * Extracted from MainActivity (god-object split, slice 3).
 *
 * NOTE: This class does NOT own PasswordAutosaveBridge — that lives with the
 * WebView construction in MainActivity and will be addressed in the security
 * batch (item #1: remove getUsername/getPassword bridge leaks).
 */
public class VaultController {

    public interface Callbacks {
        /** @return the host of the currently active tab, or null if none. */
        @Nullable String getCurrentHost();

        /** Copy to system clipboard and show a toast. */
        void copyToClipboard(@NonNull String value, @NonNull String toastMessage);
    }

    private final MainActivity activity;
    private final SecureCredentialManager credentials;
    private final Executor backgroundExecutor;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VaultController(@NonNull MainActivity activity,
                           @NonNull SecureCredentialManager credentials,
                           @NonNull Executor backgroundExecutor,
                           @NonNull Callbacks callbacks) {
        this.activity = activity;
        this.credentials = credentials;
        this.backgroundExecutor = backgroundExecutor;
        this.callbacks = callbacks;
    }

    // ------------------------------------------------------------------------
    // Per-site account picker
    // ------------------------------------------------------------------------
    public void showVaultForCurrentSite() {
        String host = callbacks.getCurrentHost();
        if (host == null || host.isEmpty()) {
            Toast.makeText(activity, "No site loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String[]> accounts = parseAccountsForHost(host);
        if (accounts.isEmpty()) {
            Toast.makeText(activity, "No saved password for " + host, Toast.LENGTH_SHORT).show();
            return;
        }

        ListView listView = new ListView(activity);
        listView.setDivider(new ColorDrawable(Color.parseColor("#333333")));
        listView.setDividerHeight(1);
        listView.setAdapter(new CredentialAdapter(accounts, host));

        new AlertDialog.Builder(activity)
                .setTitle("Saved accounts for " + host)
                .setView(listView)
                .setNegativeButton("Close", null)
                .show();
    }

    @NonNull
    private List<String[]> parseAccountsForHost(@NonNull String host) {
        List<String[]> accounts = new ArrayList<>();
        if (credentials == null) return accounts;

        Set<String> seen = new LinkedHashSet<>();
        List<String> variants = new ArrayList<>();
        variants.add(host);
        variants.add(host.startsWith("www.") ? host.substring(4) : "www." + host);

        for (String variant : variants) {
            try {
                JSONArray arr = new JSONArray(credentials.getAllAccountsForHost(variant));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String username = obj.optString("username", "");
                    String password = obj.optString("password", "");
                    if (!username.isEmpty() && seen.add(variant + "\u0001" + username)) {
                        accounts.add(new String[]{username, password});
                    }
                }
            } catch (Exception ignored) {}
        }
        return accounts;
    }

    // ------------------------------------------------------------------------
    // "Saved Passwords" manager dialog
    // ------------------------------------------------------------------------
    /**
     * Shows a list of every (host, username) pair in the encrypted vault.
     *
     * BUGFIX (slice 3): the old implementation read the legacy
     * `secure_vault.dat` file directly. That file is deleted by
     * SecureCredentialManager.migrateLegacyVault() on first launch, so the
     * dialog always showed "No passwords saved yet" for upgraded users.
     * We now read from the live encrypted store via getAllCredentialsAsJson().
     */
    public void showSavedPasswordsDialog() {
        if (credentials == null) return;

        backgroundExecutor.execute(() -> {
            List<String[]> entries = new ArrayList<>();   // [host, username]
            try {
                JSONArray arr = new JSONArray(credentials.getAllCredentialsAsJson());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String host = obj.optString("host", "");
                    String user = obj.optString("username", "");
                    if (!host.isEmpty() && !user.isEmpty()) {
                        entries.add(new String[]{host, user});
                    }
                }
            } catch (Exception ignored) {}

            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (entries.isEmpty()) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Saved Passwords")
                            .setMessage("No passwords saved yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                List<String> displayList = new ArrayList<>();
                for (String[] e : entries) displayList.add(e[0] + " (" + e[1] + ")");

                android.widget.ArrayAdapter<String> adapter =
                        new android.widget.ArrayAdapter<>(
                                activity, android.R.layout.simple_list_item_1, displayList);
                ListView listView = new ListView(activity);
                listView.setAdapter(adapter);

                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("Saved Passwords")
                        .setView(listView)
                        .setPositiveButton("Close", null)
                        .create();

                listView.setOnItemClickListener((parent, view, position, id) -> {
                    String[] entry = entries.get(position);
                    showCredentialDetailDialog(entry[0], entry[1], dialog);
                });

                dialog.show();
            });
        });
    }

    private void showCredentialDetailDialog(@NonNull String host,
                                            @NonNull String username,
                                            @Nullable AlertDialog parentDialog) {
        backgroundExecutor.execute(() -> {
            String pass = credentials.getPassword(host);

            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                new AlertDialog.Builder(activity)
                        .setTitle(host)
                        .setMessage("Username: " + username + "\nPassword: " + pass)
                        .setNegativeButton("Delete", (d, w) -> {
                            backgroundExecutor.execute(() -> {
                                credentials.deleteCredentials(host, username);
                                mainHandler.post(() -> {
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    Toast.makeText(activity, "Credentials deleted",
                                            Toast.LENGTH_SHORT).show();
                                    if (parentDialog != null) parentDialog.dismiss();
                                    showSavedPasswordsDialog();   // refresh
                                });
                            });
                        })
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    // ------------------------------------------------------------------------
    // Row adapter
    // ------------------------------------------------------------------------
    private class CredentialAdapter extends BaseAdapter {
        private final List<String[]> accounts;
        private final String host;

        CredentialAdapter(List<String[]> accounts, String host) {
            this.accounts = accounts;
            this.host = host;
        }

        @Override public int getCount() { return accounts.size(); }
        @Override public Object getItem(int p) { return accounts.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            String[] account = accounts.get(position);
            String username = account[0];
            String password = account[1];

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));

            TextView userView = new TextView(activity);
            userView.setText(username);
            userView.setTextColor(Color.WHITE);
            userView.setTextSize(15);
            LinearLayout.LayoutParams userParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            row.addView(userView, userParams);

            Button copyId = makeSmallButton("ID");
            copyId.setOnClickListener(v ->
                    callbacks.copyToClipboard(username, "Username copied for " + host));
            row.addView(copyId);

            Button copyPass = makeSmallButton("Pass");
            copyPass.setOnClickListener(v ->
                    callbacks.copyToClipboard(password, "Password copied for " + host));
            row.addView(copyPass);

            return row;
        }

        private Button makeSmallButton(String text) {
            Button button = new Button(activity);
            button.setText(text);
            button.setTextColor(Color.WHITE);
            button.setAllCaps(false);
            button.setTextSize(12);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor("#2a2a2a"));
            bg.setCornerRadius(dp(8));
            button.setBackground(bg);

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(56), dp(40));
            params.setMargins(dp(4), 0, 0, 0);
            button.setLayoutParams(params);
            return button;
        }
    }

    private int dp(int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
