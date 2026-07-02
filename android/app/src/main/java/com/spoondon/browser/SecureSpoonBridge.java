package com.spoondon.browser;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.concurrent.CountDownLatch;
import org.json.JSONArray;
import org.json.JSONObject;

public class SecureSpoonBridge {
    private final WebView webView;
    private final SecureCredentialManager credentialManager;

    public SecureSpoonBridge(WebView webView, SecureCredentialManager credentialManager) {
        this.webView = webView;
        this.credentialManager = credentialManager;
    }

    /**
     * Helper framework method to securely extract the authentic web domain directly
     * from the native WebView engine instance, bypassing untrusted JS input variables.
     */
    private String getTrueHost() {
        final String[] hostContainer = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        
        webView.post(() -> {
            try {
                String currentUrl = webView.getUrl();
                if (currentUrl != null) {
                    android.net.Uri uri = android.net.Uri.parse(currentUrl);
                    String host = uri.getHost();
                    if (host != null) {
                        String cleanHost = host.toLowerCase().trim();
                        if (cleanHost.startsWith("www.")) {
                            cleanHost = cleanHost.substring(4);
                        }
                        hostContainer[0] = cleanHost;
                    }
                }
            } catch (Exception ignored) {}
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return hostContainer[0] != null ? hostContainer[0] : "";
    }

    @JavascriptInterface
    public String getAvailableUsernames(String obfuscatedHost) {
        try {
            // SECURITY ENFORCEMENT: Override JS input parameters with validated native host context
            String host = getTrueHost();
            if (host.isEmpty()) return "[]";

            String rawJson = credentialManager.getAllAccountsForHost(host);
            JSONArray inputApp = new JSONArray(rawJson);
            JSONArray safeOutput = new JSONArray();
            
            for (int i = 0; i < inputApp.length(); i++) {
                JSONObject account = inputApp.getJSONObject(i);
                JSONObject safeAccount = new JSONObject();
                safeAccount.put("username", account.getString("username"));
                safeOutput.put(safeAccount);
            }
            return safeOutput.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @JavascriptInterface
    public void requestPasswordFill(String obfuscatedHost, final String username) {
        if (username == null) return;
        
        // SECURITY ENFORCEMENT: Enforce internal host verification
        final String host = getTrueHost();
        if (host.isEmpty()) return;
        
        // PERFORMANCE FIX: Handle JSON processing and decoding operations entirely on the background thread
        try {
            String rawJson = credentialManager.getAllAccountsForHost(host);
            JSONArray array = new JSONArray(rawJson);
            String cleartextPassword = "";
            
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (username.equals(obj.getString("username"))) {
                    cleartextPassword = obj.getString("password");
                    break;
                }
            }
            
            if (!cleartextPassword.isEmpty()) {
                String escapedPass = cleartextPassword.replace("'", "\\'");
                String escapedUser = username.replace("'", "\\'");
                
                String fillJs = "javascript:(function() {" +
                    "   function findInputs(root) {" +
                    "       var inputs = Array.from(root.querySelectorAll('input'));" +
                    "       root.querySelectorAll('*').forEach(function(el) { if (el.shadowRoot) inputs = inputs.concat(findInputs(el.shadowRoot)); });" +
                    "       return inputs;" +
                    "   }" +
                    "   function forceVal(el, val) {" +
                    "       el.value = val;" +
                    "       ['input', 'change', 'blur'].forEach(function(t) { el.dispatchEvent(new Event(t, { bubbles: true })); });" +
                    "   }" +
                    "   var all = findInputs(document);" +
                    "   all.forEach(function(i) {" +
                    "       var t = (i.type || '').toLowerCase();" +
                    "       if (t === 'password') forceVal(i, '" + escapedPass + "');" +
                    "       if (['text', 'email', 'tel'].indexOf(t) !== -1 && (i.value === '" + escapedUser + "' || !i.value)) forceVal(i, '" + escapedUser + "');" +
                    "   });" +
                    "})();";

                // Main UI Thread is utilized exclusively for running the evaluation snippet
                webView.post(() -> webView.evaluateJavascript(fillJs, null));
            }
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void saveLogin(String obfuscatedHost, String username, String password) {
        if (username == null || password == null || username.length() < 2 || password.length() < 3) {
            return; 
        }
        String host = getTrueHost();
        if (!host.isEmpty()) {
            credentialManager.saveCredentials(host, username, password);
        }
    }

    @JavascriptInterface
    public void deleteLogin(String obfuscatedHost, String username) {
        if (username == null) return;
        String host = getTrueHost();
        if (!host.isEmpty()) {
            credentialManager.deleteCredentials(host, username);
        }
    }

    @JavascriptInterface
    public void editLoginPassword(String obfuscatedHost, String username, String newPassword) {
        if (username == null || newPassword == null) return;
        String host = getTrueHost();
        if (!host.isEmpty()) {
            credentialManager.editCredentialPassword(host, username, newPassword);
        }
    }
}
