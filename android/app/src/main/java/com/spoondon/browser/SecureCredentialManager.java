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
        if (host == null || host.isEmpty()) return;
        memoryPrefs.put(host + "_user", username != null ? username : "");
        memoryPrefs.put(host + "_pass", password != null ? password : "");
        saveVault();
    }

    public synchronized String getUsername(String host) {
        String res = memoryPrefs.get(host + "_user");
        return res != null ? res : "";
    }

    public synchronized String getPassword(String host) {
        String res = memoryPrefs.get(host + "_pass");
        return res != null ? res : "";
    }

    public synchronized void clearCredentials(String host) {
        if (host == null) return;
        memoryPrefs.remove(host + "_user");
        memoryPrefs.remove(host + "_pass");
        saveVault();
    }

    public synchronized boolean exportToCSV(File outputFile) {
        FileWriter writer = null;
        try {
            writer = new FileWriter(outputFile);
            writer.append("url,username,password\n");

            for (String key : memoryPrefs.keySet()) {
                if (key.endsWith("_user")) {
                    String host = key.substring(0, key.lastIndexOf("_user"));
                    String username = memoryPrefs.get(key);
                    String password = memoryPrefs.get(host + "_pass");
                    if (password == null) password = "";

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
                                        memoryPrefs.put(host + "_user", username);
                                        memoryPrefs.put(host + "_pass", password);
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
                            memoryPrefs.put(host + "_user", username);
                            memoryPrefs.put(host + "_pass", password);
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
