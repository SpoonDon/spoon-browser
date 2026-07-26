package com.spoondon.browser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // Pre-warm WebView safely on the Main UI Thread
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
        // Instantiating a WebView initializes the Chromium engine.
        // This MUST be done on the Main UI thread.        
                new android.webkit.WebView(getApplicationContext());    
            } catch (Exception ignored) {}
        });

        SharedPreferences prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        boolean showSplash = prefs.getBoolean("show_splash_screen", true);

        if (!showSplash) {
            splashScreen.setKeepOnScreenCondition(() -> false);
            routeToMainInstant();
            return;
        }

        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView tagline = findViewById(R.id.splash_tagline);

        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_fade_in);
        Animation textAnim = AnimationUtils.loadAnimation(this, R.anim.splash_text_fade_in);

        logoAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> routeToMainWithFade(), 500);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        logo.startAnimation(logoAnim);
        title.startAnimation(textAnim);
        tagline.startAnimation(textAnim);
    }

    private void routeToMainWithFade() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void routeToMainInstant() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
        overridePendingTransition(0, 0);
    }
}
