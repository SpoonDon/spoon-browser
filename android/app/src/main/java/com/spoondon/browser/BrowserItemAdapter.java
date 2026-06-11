package com.spoondon.browser;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import java.util.ArrayList;

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

        layout.addView(
                titleView
        );

        return layout;
    }
}
