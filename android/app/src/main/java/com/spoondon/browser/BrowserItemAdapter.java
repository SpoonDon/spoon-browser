package com.spoondon.browser;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import java.util.ArrayList;
import java.net.URI;

public class BrowserItemAdapter
        extends ArrayAdapter<BrowserItem> {

    public BrowserItemAdapter(
            Context context,
            ArrayList<BrowserItem> items) {

        super(
                context,
                0,
                items
        );
    }

    @Override
    public View getView(
            int position,
            View convertView,
            ViewGroup parent) {

        BrowserItem item =
                getItem(position);

        LinearLayout layout =
                new LinearLayout(
                        getContext()
                );

        layout.setOrientation(
                LinearLayout.VERTICAL
        );

        layout.setPadding(
                32,
                24,
                32,
                24
        );

        layout.setGravity(
                Gravity.CENTER_VERTICAL
        );

        TextView titleView =
                new TextView(
                        getContext()
                );

        titleView.setText(
                item.title
        );

        titleView.setTextSize(
                18
        );

        TextView urlView =
          new TextView(
                getContext()
        );

        try {

        String host =
                URI.create(
                        item.url
                ).getHost();

        if (host != null &&
                host.startsWith(
                        "www."
                )) {

            host =
                    host.substring(
                            4
                    );
        }

        urlView.setText(
                host
        );

        } catch (Exception e) {

            urlView.setText(
                    item.url
            );
        }

        urlView.setTextSize(
                12
        );

        urlView.setAlpha(
                0.7f
        );

        layout.addView(
                titleView
        );

        layout.addView(
                urlView
        );

        return layout;
    }
}
