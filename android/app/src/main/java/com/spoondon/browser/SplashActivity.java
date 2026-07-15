package com.spoondon.browser;

import com.spoondon.browser.R;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // CHECK PREFERENCES: Kill animation if user disabled it
        android.content.SharedPreferences prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        boolean showSplash = prefs.getBoolean("show_splash_screen", true);
        
        if (!showSplash) {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            return; // Stop executing the rest of the file
        }

        // If enabled, proceed with the normal splash screen
        setContentView(R.layout.activity_splash);

        // Pre-warm the heavy Chromium WebKit engine on a background thread
        new Thread(() -> {
            try {
                // Instantiating a dummy WebView forces Android to load the Chromium libraries into RAM early
                new android.webkit.WebView(getApplicationContext());
            } catch (Exception ignored) {}
        }).start();

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView tagline = findViewById(R.id.splash_tagline);

        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_fade_in);
        Animation textAnim = AnimationUtils.loadAnimation(this, R.anim.splash_text_fade_in);

        logo.startAnimation(logoAnim);
        title.startAnimation(textAnim);
        tagline.startAnimation(textAnim);

        // Extended from 1200ms to 3000ms to ensure the tagline is readable
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, 3000);
    }
}
