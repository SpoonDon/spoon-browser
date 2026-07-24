package com.spoondon.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;

public class ShortcutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        String url = intent.getStringExtra("url");
        
        if (url != null) {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.setAction(Intent.ACTION_VIEW);
            mainIntent.setData(android.net.Uri.parse(url));
            startActivity(mainIntent);
        }
        
        finish();
    }
}
