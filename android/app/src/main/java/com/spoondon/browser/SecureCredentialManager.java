package com.spoondon.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
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
            MasterKey masterKey = new MasterKey.Builder(this.context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encryptedPrefs = EncryptedSharedPreferences.create(
                    this.context,
                    "spoon_secure_vault",
                    masterKey,
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
                        
                        if (key.endsWith("_user")) {
                            String host = key.substring(0, key.lastIndexOf("_" + decryptedVal + "_user"));
                            editor.putString(host + "_primary_user", decryptedVal);
                        }
                    }
                }
            }
            editor.apply();
            legacyFile.delete();
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
            .putString(host + "_primary_user", cleanUser)
            .apply();
    }

    public synchronized String getUsername(String host) {
        if (!isReady || host == null) return "";
        return encryptedPrefs.getString(host + "_primary_user", "");
    }

    public synchronized String getPassword(String host) {
        if (!isReady || host == null) return "";
        String username = getUsername(host);
        if (username.isEmpty()) return "";
        return encryptedPrefs.getString(host + "_" + username + "_pass", "");
    }

    public synchronized String getAllAccountsForHost(String host) {
        if (!isReady || host == null || host.isEmpty()) return "[]";
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            java.util.Map<String, ?> all = encryptedPrefs.getAll();
            for (String key : all.keySet()) {
                if (key.startsWith(host + "_") && key.endsWith("_user") && !key.endsWith("_primary_user")) {
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
                if (key.endsWith("_user") && !key.endsWith("_primary_user")) {
                    String username = (String) all.get(key);
                    if (username != null && !username.isEmpty()) {
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
                if (key.endsWith("_user") && !key.endsWith("_primary_user")) {
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
        
        SharedPreferences.Editor editor = encryptedPrefs.edit();
        editor.remove(host + "_" + username + "_pass");
        editor.remove(host + "_" + username + "_user");
        
        if (username.equals(encryptedPrefs.getString(host + "_primary_user", ""))) {
            editor.remove(host + "_primary_user");
        }
        editor.commit(); 
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

            String[] headers = headerLine.toLowerCase().replace("\"", "").split(",");
            int urlIndex = -1, usernameIndex = -1, passwordIndex = -1;

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim();
                if (h.equals("url") || h.equals("login_uri") || h.equals("website") || h.contains("url")) urlIndex = i;
                else if (h.equals("username") || h.equals("login_username") || h.equals("email") || h.contains("user")) usernameIndex = i;
                else if (h.equals("password") || h.equals("login_password") || h.contains("pass")) passwordIndex = i;
            }

            if (urlIndex == -1 || usernameIndex == -1 || passwordIndex == -1) {
                return false;
            }

            android.content.SharedPreferences.Editor editor = encryptedPrefs.edit();
            String line;
            
            String csvRegex = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] columns = line.split(csvRegex, -1);
                
                try {
                    if (columns.length > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                        
                        String host = columns[urlIndex].replaceAll("^\"|\"$", "").trim();
                        String username = columns[usernameIndex].replaceAll("^\"|\"$", "").trim();
                        
                        String password = columns[passwordIndex].replaceAll("^\"|\"$", "").replace("\"\"", "\"");

                        if (!host.isEmpty() && !username.isEmpty()) {
                            if (host.contains("://")) host = host.substring(host.indexOf("://") + 3);
                            if (host.contains("/")) host = host.split("/")[0];
                            host = host.toLowerCase().trim();

                            editor.putString(host + "_" + username + "_user", username);
                            editor.putString(host + "_" + username + "_pass", password);
                            editor.putString(host + "_primary_user", username);
                        }
                    }
                } catch (Exception e) {
                }
            }

            editor.apply();
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
