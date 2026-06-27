package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class SecureCredentialManager {
    private static final String SECRET_FILE_NAME = "secure_user_credentials";
    private SharedPreferences encryptedPrefs;

    public SecureCredentialManager(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);

            encryptedPrefs = EncryptedSharedPreferences.create(
                    SECRET_FILE_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            encryptedPrefs = context.getSharedPreferences(SECRET_FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    public void saveCredentials(String host, String username, String password) {
        if (encryptedPrefs != null && host != null) {
            encryptedPrefs.edit()
                .putString(host + "_user", username)
                .putString(host + "_pass", password)
                .apply();
        }
    }

    public String getUsername(String host) {
        return encryptedPrefs != null ? encryptedPrefs.getString(host + "_user", "") : "";
    }

    public String getPassword(String host) {
        return encryptedPrefs != null ? encryptedPrefs.getString(host + "_pass", "") : "";
    }

    public void clearCredentials(String host) {
        if (encryptedPrefs != null && host != null) {
            encryptedPrefs.edit()
                .remove(host + "_user")
                .remove(host + "_pass")
                .apply();
        }
    }

    public boolean exportToCSV(File outputFile) {
        if (encryptedPrefs == null) return false;

        Map<String, ?> allEntries = encryptedPrefs.getAll();
        FileWriter writer = null;
        try {
            writer = new FileWriter(outputFile);
            writer.append("url,username,password\n");

            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("_user")) {
                    String host = key.substring(0, key.lastIndexOf("_user"));
                    String username = entry.getValue().toString();
                    String password = encryptedPrefs.getString(host + "_pass", "");

                    username = username.replace("\"", "\"\"");
                    password = password.replace("\"", "\"\"");

                    writer.append(String.format("\"%s\",\"%s\",\"%s\"\n", host, username, password));
                }
            }
            writer.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    public boolean importFromCSVStream(InputStream inputStream) {
        if (encryptedPrefs == null || inputStream == null) return false;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream));
            SharedPreferences.Editor editor = encryptedPrefs.edit();

            int urlIndex = -1;
            int usernameIndex = -1;
            int passwordIndex = -1;
            boolean isHeader = true;

            List<String> tokens = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            int ch;

            // Character-level processing completely bypasses line break tracking issues
            while ((ch = reader.read()) != -1) {
                char c = (char) ch;

                if (c == '"') {
                    if (inQuotes) {
                        // Lookahead for double escape quote pairs ("")
                        reader.mark(1);
                        int nextCh = reader.read();
                        if (nextCh == '"') {
                            sb.append('"');
                        } else {
                            inQuotes = false;
                            if (nextCh != -1) reader.reset();
                        }
                    } else {
                        inQuotes = true;
                    }
                } else if (c == ',' && !inQuotes) {
                    tokens.add(sb.toString().trim());
                    sb.setLength(0);
                } else if ((c == '\n' || c == '\r') && !inQuotes) {
                    if (c == '\r') {
                        reader.mark(1);
                        int nextCh = reader.read();
                        if (nextCh != '\n' && nextCh != -1) reader.reset();
                    }
                    tokens.add(sb.toString().trim());
                    sb.setLength(0);

                    if (!tokens.isEmpty() && !(tokens.size() == 1 && tokens.get(0).isEmpty())) {
                        if (isHeader) {
                            for (int i = 0; i < tokens.size(); i++) {
                                String h = tokens.get(i).toLowerCase();
                                if (h.contains("url")) urlIndex = i;
                                else if (h.contains("username") || h.contains("user")) usernameIndex = i;
                                else if (h.contains("password") || h.contains("pass")) passwordIndex = i;
                            }
                            isHeader = false;
                            if (urlIndex == -1 || usernameIndex == -1 || passwordIndex == -1) {
                                return false;
                            }
                        } else {
                            if (tokens.size() > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                                String host = tokens.get(urlIndex);
                                String username = tokens.get(usernameIndex);
                                String password = tokens.get(passwordIndex);
                                if (!host.isEmpty()) {
                                    editor.putString(host + "_user", username);
                                    editor.putString(host + "_pass", password);
                                }
                            }
                        }
                    }
                    tokens.clear();
                } else {
                    sb.append(c);
                }
            }

            // Flush out lingering rows if file is missing trailing endline markup
            if (sb.length() > 0 || !tokens.isEmpty()) {
                tokens.add(sb.toString().trim());
                if (!isHeader && tokens.size() > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                    String host = tokens.get(urlIndex);
                    String username = tokens.get(usernameIndex);
                    String password = tokens.get(passwordIndex);
                    if (!host.isEmpty()) {
                        editor.putString(host + "_user", username);
                        editor.putString(host + "_pass", password);
                    }
                }
            }

            editor.apply();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }
}
