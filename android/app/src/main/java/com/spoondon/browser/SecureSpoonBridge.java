package com.spoondon.browser;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

public class SecureSpoonBridge {
    private final WebView webView;
    private final SecureCredentialManager credentialManager;

    public SecureSpoonBridge(WebView webView, SecureCredentialManager credentialManager) {
        this.webView = webView;
        this.credentialManager = credentialManager;
    }

    @JavascriptInterface
    public String getAvailableUsernames(String host) {
        try {
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
    public void requestPasswordFill(final String host, final String username) {
        if (host == null || username == null) return;
        
        final String rawJson = credentialManager.getAllAccountsForHost(host);
        
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
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
                            "   var passFields = document.querySelectorAll(\"input[type='password']\");" +
                            "   var userFields = document.querySelectorAll(\"input[type='text'], input[type='email'], input[type='tel']\");" +
                            "   userFields.forEach(function(uf) {" +
                            "       if(uf.value === '" + escapedUser + "' || !uf.value) {" +
                            "           uf.value = '" + escapedUser + "';" +
                            "       }" +
                            "   });" +
                            "   passFields.forEach(function(pf) { pf.value = '" + escapedPass + "'; });" +
                            "})();";
                        webView.evaluateJavascript(fillJs, null);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    @JavascriptInterface
    public void saveLogin(String host, String username, String password) {
        if (username == null || password == null || username.length() < 2 || password.length() < 3) {
            return; 
        }
        credentialManager.saveCredentials(host, username, password);
    }
}

