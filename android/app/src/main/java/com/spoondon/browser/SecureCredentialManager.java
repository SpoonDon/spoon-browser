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
            
            int urlIndex = -1;
            int usernameIndex = -1;
            int passwordIndex = -1;
            boolean isHeader = true;
            
            SharedPreferences.Editor editor = encryptedPrefs.edit();

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                List<String> tokens = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                boolean inQuotes = false;
                
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            inQuotes = !inQuotes;
                        }
                    } else if (c == ',' && !inQuotes) {
                        tokens.add(sb.toString().trim());
                        sb.setLength(0);
                    } else if (c != '\r') {
                        sb.append(c);
                    }
                }
                tokens.add(sb.toString().trim());

                // Read the header dynamically to map index locations
                if (isHeader) {
                    for (int i = 0; i < tokens.size(); i++) {
                        String header = tokens.get(i).toLowerCase();
                        if (header.contains("url")) urlIndex = i;
                        else if (header.contains("username") || header.contains("user")) usernameIndex = i;
                        else if (header.contains("password") || header.contains("pass")) passwordIndex = i;
                    }
                    isHeader = false;
                    
                    // Fallback to defaults if headers don't match standard names
                    if (urlIndex == -1 || usernameIndex == -1 || passwordIndex == -1) {
                        return false; 
                    }
                    continue;
                }

                // Process data rows based on dynamically mapped header indexes
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
