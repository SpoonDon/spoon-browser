package com.spoondon.browser;

import android.content.Context;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class SecureCredentialManager {
    private static final String STORAGE_FILE_NAME = "secure_vault.dat";
    private final File vaultFile;
    private final HashMap<String, String> memoryPrefs = new HashMap<>();
    private SecretKeySpec secretKey;

    public SecureCredentialManager(Context context) {
        this.vaultFile = new File(context.getFilesDir(), STORAGE_FILE_NAME);
        try {
            String rawKey = (context.getPackageName() + "SpoonSaltString").substring(0, 16);
            this.secretKey = new SecretKeySpec(rawKey.getBytes(StandardCharsets.UTF_8), "AES");
            loadVault();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void loadVault() {
        if (!vaultFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(vaultFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    memoryPrefs.put(parts[0], decrypt(parts[1]));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void saveVault() {
        try (FileOutputStream fos = new FileOutputStream(vaultFile);
             FileWriter writer = new FileWriter(fos.getFD())) {
            for (Map.Entry<String, String> entry : memoryPrefs.entrySet()) {
                if (entry.getValue() != null) {
                    writer.write(entry.getKey() + "=" + encrypt(entry.getValue()) + "\n");
                }
            }
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String encrypt(String val) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(val.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encrypted, Base64.DEFAULT).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String decrypt(String val) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decoded = Base64.decode(val, Base64.DEFAULT);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized void saveCredentials(String host, String username, String password) {
        if (host == null || host.isEmpty() || username == null || username.isEmpty()) return;
        String cleanUser = username.trim();
        memoryPrefs.put(host + "_" + cleanUser + "_user", cleanUser);
        memoryPrefs.put(host + "_" + cleanUser + "_pass", password != null ? password : "");
        saveVault();
    }

    /**
     * Deletes a specific saved login credential record from the vault.
     */
    public synchronized void deleteCredentials(String host, String username) {
        if (host == null || username == null) return;
        String cleanUser = username.trim();
        memoryPrefs.remove(host + "_" + cleanUser + "_user");
        memoryPrefs.remove(host + "_" + cleanUser + "_pass");
        saveVault();
    }

    /**
     * Modifies an existing saved password record inside the vault.
     */
    public synchronized void editCredentialPassword(String host, String username, String newPassword) {
        if (host == null || username == null || newPassword == null) return;
        String cleanUser = username.trim();
        memoryPrefs.put(host + "_" + cleanUser + "_pass", newPassword);
        saveVault();
    }

    public synchronized String getUsername(String host) {
        // Fallback method used by legacy calls: returns the first user found
        if (host == null) return "";
        for (String key : memoryPrefs.keySet()) {
            if (key.startsWith(host + "_") && key.endsWith("_user")) {
                return memoryPrefs.get(key);
            }
        }
        return "";
    }

    public synchronized String getPassword(String host) {
        // Fallback method used by legacy calls: returns the first pass found
        if (host == null) return "";
        for (String key : memoryPrefs.keySet()) {
            if (key.startsWith(host + "_") && key.endsWith("_pass")) {
                return memoryPrefs.get(key);
            }
        }
        return "";
    }

    public synchronized String getAllAccountsForHost(String host) {
        if (host == null || host.isEmpty()) return "[]";
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            String userKeySuffix = "_user";
            for (String key : memoryPrefs.keySet()) {
                if (key.startsWith(host + "_") && key.endsWith(userKeySuffix)) {
                    String username = memoryPrefs.get(key);
                    if (username != null && !username.isEmpty()) {
                        String password = memoryPrefs.get(host + "_" + username + "_pass");
                        
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("username", username);
                        obj.put("password", password != null ? password : "");
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
        try {
            for (String key : memoryPrefs.keySet()) {
                if (key.endsWith("_user")) {
                    String username = memoryPrefs.get(key);
                    if (username != null && !username.isEmpty()) {
                        int firstUnderscore = key.indexOf("_");
                        if (firstUnderscore != -1) {
                            String host = key.substring(0, key.lastIndexOf("_user"));
                            if (host.contains("_")) {
                                host = host.substring(0, host.indexOf("_"));
                            }

                            String password = memoryPrefs.get(host + "_" + username + "_pass");

                            org.json.JSONObject obj = new org.json.JSONObject();
                            obj.put("host", host);
                            obj.put("username", username);
                            obj.put("password", password != null ? password : "");
                            array.put(obj);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return array.toString();
    }

    public synchronized void deleteCredentials(String host, String username) {
        if (host == null || username == null) return;
        try {
            memoryPrefs.remove(host + "_" + username + "_pass");
            String targetKey = null;
            for (String key : memoryPrefs.keySet()) {
                if (key.startsWith(host + "_") && key.endsWith("_user")) {
                    if (username.equals(memoryPrefs.get(key))) {
                        targetKey = key;
                        break;
                    }
                }
            }
            if (targetKey != null) {
                memoryPrefs.remove(targetKey);
            }
            // Note: We use saveVault() since line 94 closed with it earlier
            saveVault(); 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void clearCredentials(String host) {
        if (host == null || host.isEmpty()) return;
        // Collect all keys matching this host prefix to clear them out safely
        java.util.List<String> keysToRemove = new java.util.ArrayList<>();
        for (String key : memoryPrefs.keySet()) {
            if (key.startsWith(host + "_")) {
                keysToRemove.add(key);
            }
        }
        for (String key : keysToRemove) {
            memoryPrefs.remove(key);
        }
        saveVault();
    }

    public synchronized boolean exportToCSV(Context context) {
        Context appContext = context.getApplicationContext();
        StringBuilder csvContent = new StringBuilder("url,username,password\n");

        for (String key : memoryPrefs.keySet()) {
            if (key.endsWith("_user")) {
                String username = memoryPrefs.get(key);
                if (username == null || username.isEmpty()) continue;
                String suffix = "_" + username + "_user";
                if (!key.endsWith(suffix)) continue;

                String host = key.substring(0, key.length() - suffix.length());
                String password = memoryPrefs.get(host + "_" + username + "_pass");
                if (password == null) password = "";

                csvContent.append(String.format("\"%s\",\"%s\",\"%s\"\n", 
                    host.replace("\"", "\"\""), 
                    username.replace("\"", "\"\""), 
                    password.replace("\"", "\"\"")));
            }
        }

        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "passwords_" + System.currentTimeMillis() + ".csv");
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);

            android.net.Uri uri = appContext.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                java.io.OutputStream out = appContext.getContentResolver().openOutputStream(uri);
                if (out != null) {
                    out.write(csvContent.toString().getBytes());
                    out.close();
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean importFromCSVStream(InputStream inputStream) {
        if (inputStream == null) return false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            int urlIndex = -1, usernameIndex = -1, passwordIndex = -1;
            boolean isHeader = true;

            List<String> tokens = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuotes = false;
            int ch;

            synchronized (this) {
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
                                        if (host.contains("://")) {
                                            host = host.substring(host.indexOf("://") + 3);
                                        }
                                        if (host.contains("/")) {
                                            host = host.split("/")[0];
                                        }
host = host.toLowerCase().trim();
if (username != null && !username.trim().isEmpty()) {
    String cleanUser = username.trim();
    memoryPrefs.put(host + "_" + cleanUser + "_user", cleanUser);
    memoryPrefs.put(host + "_" + cleanUser + "_pass", password != null ? password : "");
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

                if (sb.length() > 0 || !tokens.isEmpty()) {
                    tokens.add(sb.toString().trim());
                    if (!isHeader && tokens.size() > Math.max(urlIndex, Math.max(usernameIndex, passwordIndex))) {
                        String host = tokens.get(urlIndex);
                        String username = tokens.get(usernameIndex);
                        String password = tokens.get(passwordIndex);

                        if (!host.isEmpty()) {
                            if (host.contains("://")) {
                                host = host.substring(host.indexOf("://") + 3);
                            }
                            if (host.contains("/")) {
                                host = host.split("/")[0];
                            }
                            host = host.toLowerCase().trim();
if (username != null && !username.trim().isEmpty()) {
    String cleanUser = username.trim();
    memoryPrefs.put(host + "_" + cleanUser + "_user", cleanUser);
    memoryPrefs.put(host + "_" + cleanUser + "_pass", password != null ? password : "");
}

                        }

                    }
                }
                saveVault();
            }
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
