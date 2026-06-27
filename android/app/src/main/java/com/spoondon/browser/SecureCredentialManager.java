package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

    public boolean importFromCSV(File inputFile) {
        if (encryptedPrefs == null || !inputFile.exists()) return false;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(inputFile));
            String line;
            boolean isHeader = true;
            SharedPreferences.Editor editor = encryptedPrefs.edit();

            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                if (line.trim().isEmpty()) continue;

                List<String> tokens = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                boolean inQuotes = false;
                
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    
                    if (c == '"') {
                        // Lookahead for double escape pairs ("")
                        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i++; // Skip the second quote
                        } else {
                            inQuotes = !inQuotes; // Toggle quote boundaries
                        }
                    } else if (c == ',' && !inQuotes) {
                        tokens.add(sb.toString().trim());
                        sb.setLength(0);
                    } else if (c != '\r') { // Ignore carriage returns from Windows exports
                        sb.append(c);
                    }
                }
                tokens.add(sb.toString().trim());

                if (tokens.size() >= 3) {
                    String host = tokens.get(0);
                    String username = tokens.get(1);
                    String password = tokens.get(2);

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
