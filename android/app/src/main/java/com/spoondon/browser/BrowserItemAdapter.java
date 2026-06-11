package com.spoondon.browser;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

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
                LinearLayout.HORIZONTAL
        );

        TextView titleView =
                new TextView(
                        getContext()
                );

        titleView.setText(
                item.title
        );

        layout.addView(
                titleView
        );

        return layout;
    }
}
