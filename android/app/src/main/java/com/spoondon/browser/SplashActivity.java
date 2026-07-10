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
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView tagline = findViewById(R.id.splash_tagline);

        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_fade_in);
        Animation textAnim = AnimationUtils.loadAnimation(this, R.anim.splash_text_fade_in);

        logo.startAnimation(logoAnim);
        title.startAnimation(textAnim);
        tagline.startAnimation(textAnim);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, 1200);
    }
}
