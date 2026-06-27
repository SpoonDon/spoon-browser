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

    /**
     * Exports all stored credentials into a standard CSV format: url,username,password
     */
    public boolean exportToCSV(File outputFile) {
        if (encryptedPrefs == null) return false;
        
        Map<String, ?> allEntries = encryptedPrefs.getAll();
        FileWriter writer = null;
        try {
            writer = new FileWriter(outputFile);
            // Write CSV Header
            writer.append("url,username,password\n");

            // Match up the keys since they are stored as host_user and host_pass
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("_user")) {
                    String host = key.substring(0, key.lastIndexOf("_user"));
                    String username = entry.getValue().toString();
                    String password = encryptedPrefs.getString(host + "_pass", "");
                    
                    // Escape basic commas or quotes in user data to keep CSV valid
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

    /**
     * Imports credentials from a standard CSV file and commits them to the secure vault
     */
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
                    isHeader = false; // Skip the CSV header row (url,username,password)
                    continue;
                }
                
                // Simple CSV splitter parsing quoted strings safely
                String[] tokens = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                if (tokens.length >= 3) {
                    String host = tokens[0].replace("\"", "").trim();
                    String username = tokens[1].replace("\"", "").trim();
                    String password = tokens[2].replace("\"", "").trim();

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
