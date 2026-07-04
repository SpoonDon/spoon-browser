package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class SecureCredentialManager {
    private SharedPreferences encryptedPrefs;
    private boolean isReady = false;
    private final Context context;

    public SecureCredentialManager(Context context) {
        this.context = context.getApplicationContext();
        try {
            // Generates or retrieves a hardware-backed key from the Android Keystore
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            encryptedPrefs = EncryptedSharedPreferences.create(
                    "spoon_secure_vault",
                    masterKeyAlias,
                    this.context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            isReady = true;
            migrateLegacyVault();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void migrateLegacyVault() {
        File legacyFile = new File(context.getFilesDir(), "secure_vault.dat");
        if (!legacyFile.exists()) return;

        try {
            // Temporarily resurrect the old key to decrypt legacy data
            String rawKey = (context.getPackageName() + "SpoonSaltString").substring(0, 16);
            SecretKeySpec secretKey = new SecretKeySpec(rawKey.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);

            SharedPreferences.Editor editor = encryptedPrefs.edit();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(legacyFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0];
                        byte[] decoded = Base64.decode(parts[1], Base64.DEFAULT);
                        String decryptedVal = new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
                        editor.putString(key, decryptedVal);
                    }
                }
            }
            editor.apply();
            legacyFile.delete(); // Permanently destroy the old weak file
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void saveCredentials(String host, String username, String password) {
        if (!isReady || host == null || host.isEmpty() || username == null || username.isEmpty()) return;
        String cleanUser = username.trim();
        encryptedPrefs.edit()
            .putString(host + "_" + cleanUser + "_user", cleanUser)
            .putString(host + "_" + cleanUser + "_pass", password != null ? password : "")
            .apply();
    }

    public synchronized String getUsername(String host) {
        if (!isReady || host == null) return "";
        java.util.Map<String, ?> all = encryptedPrefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(host + "_") && key.endsWith("_user")) {
                return (String) all.get(key);
            }
        }
        return "";
    }

    public synchronized String getPassword(String host) {
        if (!isReady || host == null) return "";
        java.util.Map<String, ?> all = encryptedPrefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(host + "_") && key.endsWith("_pass")) {
                return (String) all.get(key);
            }
        }
        return "";
    }

    public synchronized String getAllAccountsForHost(String host) {
        if (!isReady || host == null || host.isEmpty()) return "[]";
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            java.util.Map<String, ?> all = encryptedPrefs.getAll();
            for (String key : all.keySet()) {
                if (key.startsWith(host + "_") && key.endsWith("_user")) {
                    String username = (String) all.get(key);
                    if (username != null && !username.isEmpty()) {
                        String password = encryptedPrefs.getString(host + "_" + username + "_pass", "");
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("username", username);
                        obj.put("password", password);
                        array.put(obj);
                    }
                }
            }
            return array.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public synchronized String getAllCredentialsAsJson() {
        org.json.JSONArray array = new org.json.JSONArray();
        if (!isReady) return array.toString();
        try {
            java.util.Map<String, ?> all = encryptedPrefs.getAll();
            for (String key : all.keySet()) {
                if (key.endsWith("_user")) {
                    String username = (String) all.get(key);
                    if (username != null && !username.isEmpty()) {
                        // FIX: Safely extract host without splitting to prevent underscore domain truncation
                        String suffix = "_" + username + "_user";
                        if (key.endsWith(suffix) && key.length() > suffix.length()) {
                            String host = key.substring(0, key.length() - suffix.length());
                            String password = encryptedPrefs.getString(host + "_" + username + "_pass", "");

                            org.json.JSONObject obj = new org.json.JSONObject();
                            obj.put("host", host);
                            obj.put("username", username);
                            obj.put("password", password);
                            array.put(obj);
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return array.toString();
    }

    public synchronized String getAllCredentialsAsCsv() {
        if (!isReady) return "url,username,password\n";
        StringBuilder csv = new StringBuilder("url,username,password\n");
        try {
            java.util.Map<String, ?> all = encryptedPrefs.getAll();
            for (String key : all.keySet()) {
                if (key.endsWith("_user")) {
                    String username = (String) all.get(key);
                    if (username == null || username.isEmpty()) continue;
                    String suffix = "_" + username + "_user";
                    if (key.endsWith(suffix) && key.length() > suffix.length()) {
                        String host = key.substring(0, key.length() - suffix.length());
                        String password = encryptedPrefs.getString(host + "_" + username + "_pass", "");
                        
                        csv.append(String.format("\"%s\",\"%s\",\"%s\"\n", 
                            host.replace("\"", "\"\""), 
                            username.replace("\"", "\"\""), 
                            password.replace("\"", "\"\"")));
                    }
                }
            }
        } catch (Exception e) {}
        return csv.toString();
    }

    public synchronized void editCredentialPassword(String host, String username, String newPassword) {
        if (!isReady || host == null || username == null || newPassword == null) return;
        encryptedPrefs.edit().putString(host + "_" + username.trim() + "_pass", newPassword).apply();
    }

    public synchronized void deleteCredentials(String host, String username) {
        if (!isReady || host == null || username == null) return;
        encryptedPrefs.edit()
            .remove(host + "_" + username + "_pass")
            .remove(host + "_" + username + "_user")
            .apply();
    }

    public synchronized void clearCredentials(String host) {
        if (!isReady || host == null || host.isEmpty()) return;
        SharedPreferences.Editor editor = encryptedPrefs.edit();
        java.util.Map<String, ?> all = encryptedPrefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(host + "_")) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public synchronized boolean importFromCSVStream(java.io.InputStream inputStream) {
        if (!isReady || inputStream == null) return false;
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            int urlIndex = -1, usernameIndex = -1, passwordIndex = -1;
            boolean isHeader = true;

            java.util.List<String> tokens = new java.util.ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            int ch;

            // Open a single batch editor for massive performance gains during bulk imports
            android.content.SharedPreferences.Editor editor = encryptedPrefs.edit();

            while ((ch = reader.read()) != -1) {
                char c = (char) ch;
                if (c == '"') {
                    if (inQuotes) {
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
                            if (urlIndex == -1 || usernameIndex == -1 || passwordIndex == -1) return false;
                        } else {
                            if (tokens.size() > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                                String host = tokens.get(urlIndex);
                                String username = tokens.get(usernameIndex);
                                String password = tokens.get(passwordIndex);

                                if (!host.isEmpty()) {
                                    if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
                                    if (host.contains("/")) host = host.split("/")[0];
                                    host = host.toLowerCase().trim();
                                    
                                    if (username != null && !username.trim().isEmpty()) {
                                        String cleanUser = username.trim();
                                        editor.putString(host + "_" + cleanUser + "_user", cleanUser);
                                        editor.putString(host + "_" + cleanUser + "_pass", password != null ? password : "");
                                    }
                                }
                            }
                        }
                    }
                    tokens.clear();
                } else {
                    sb.append(c);
                }
            }
            
            // Catch the final line if the CSV doesn't end with a newline character
            if (sb.length() > 0 || !tokens.isEmpty()) {
                tokens.add(sb.toString().trim());
                if (!isHeader && tokens.size() > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                    String host = tokens.get(urlIndex);
                    String username = tokens.get(usernameIndex);
                    String password = tokens.get(passwordIndex);

                    if (!host.isEmpty()) {
                        if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
                        if (host.contains("/")) host = host.split("/")[0];
                        host = host.toLowerCase().trim();
                        
                        if (username != null && !username.trim().isEmpty()) {
                            String cleanUser = username.trim();
                            editor.putString(host + "_" + cleanUser + "_user", cleanUser);
                            editor.putString(host + "_" + cleanUser + "_pass", password != null ? password : "");
                        }
                    }
                }
            }
            
            // Commit all imported passwords natively to the hardware keystore in one action
            editor.apply();
            return true;
        } catch (java.io.IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (java.io.IOException ignored) {}
            }
        }
    }
}
