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
        // 1. Initialize Android 12+ Native Splash Screen (MUST be before super.onCreate)
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // 2. Safe Chromium Engine Pre-warming
        Looper.myQueue().addIdleHandler(() -> {
            try {
                new android.webkit.WebView(getApplicationContext());
            } catch (Exception ignored) {}
            return false; 
        });

        // 3. Check Preferences
        SharedPreferences prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE);
        boolean showSplash = prefs.getBoolean("show_splash_screen", true);

        if (!showSplash) {
            splashScreen.setKeepOnScreenCondition(() -> true);
            routeToMain();
            return;
        }

        // 4. Load Custom UI
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView tagline = findViewById(R.id.splash_tagline);

        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_fade_in);
        Animation textAnim = AnimationUtils.loadAnimation(this, R.anim.splash_text_fade_in);

        // 5. Dynamic Transition
        logoAnim.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> routeToMain(), 500);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        logo.startAnimation(logoAnim);
        title.startAnimation(textAnim);
        tagline.startAnimation(textAnim);
    }

    private void routeToMain() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
