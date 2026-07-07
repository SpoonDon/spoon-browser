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
        
        // 🛠️ THE FIX: Replaced .apply() with .commit() for synchronous, immediate deletion
        encryptedPrefs.edit()
            .remove(host + "_" + username + "_pass")
            .remove(host + "_" + username + "_user")
            .commit(); 
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

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return false;

            // 1. Map columns dynamically based on the header
            String[] headers = headerLine.toLowerCase().replace("\"", "").split(",");
            int urlIndex = -1, usernameIndex = -1, passwordIndex = -1;

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim();
                // Broader matching to support Chrome, Bitwarden, Firefox, etc.
                if (h.equals("url") || h.equals("login_uri") || h.equals("website") || h.contains("url")) urlIndex = i;
                else if (h.equals("username") || h.equals("login_username") || h.equals("email") || h.contains("user")) usernameIndex = i;
                else if (h.equals("password") || h.equals("login_password") || h.contains("pass")) passwordIndex = i;
            }

            if (urlIndex == -1 || usernameIndex == -1 || passwordIndex == -1) {
                return false; // Invalid CSV format
            }

            android.content.SharedPreferences.Editor editor = encryptedPrefs.edit();
            String line;
            
            // 2. The Magic Regex: Splits by commas ONLY if they are outside of quotation marks
            String csvRegex = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] columns = line.split(csvRegex, -1);
                
                try {
                    // Ensure the row actually has enough columns to avoid IndexOutOfBounds
                    if (columns.length > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                        
                        // Extract and strip bounding quotation marks ONLY (replaceAll("^\"|\"$", ""))
                        // This preserves intentional quotes inside the password!
                        String host = columns[urlIndex].replaceAll("^\"|\"$", "").trim();
                        String username = columns[usernameIndex].replaceAll("^\"|\"$", "").trim();
                        
                        // NEVER trim passwords, they might intentionally start/end with spaces!
                        // Unescape double quotes ("") back to single quotes (")
                        String password = columns[passwordIndex].replaceAll("^\"|\"$", "").replace("\"\"", "\"");

                        if (!host.isEmpty() && !username.isEmpty()) {
                            // Clean host URL down to the base domain
                            if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
                            if (host.contains("/")) host = host.split("/")[0];
                            host = host.toLowerCase().trim();

                            editor.putString(host + "_" + username + "_user", username);
                            editor.putString(host + "_" + username + "_pass", password);
                        }
                    }
                } catch (Exception e) {
                    // Silently skip malformed rows so the rest of the file continues to import
                }
            }

            // Commit all imported passwords natively to the hardware keystore in one action
            editor.apply();
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
