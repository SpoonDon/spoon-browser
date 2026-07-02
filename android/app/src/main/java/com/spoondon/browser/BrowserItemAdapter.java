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

public class BrowserItemAdapter extends ArrayAdapter<BrowserItem> {

    // OPTIMIZATION: ViewHolder caches view references to stop redundant layout lookups and memory allocations
    private static class ViewHolder {
        TextView titleView;
        TextView urlView;
    }

    public BrowserItemAdapter(Context context, ArrayList<BrowserItem> items) {
        super(context, 0, items);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        BrowserItem item = getItem(position);
        LinearLayout layout;
        ViewHolder holder;

        // 1. If convertView is null, construct the interface elements once
        if (convertView == null) {
            layout = new LinearLayout(getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(32, 24, 32, 24);
            layout.setGravity(Gravity.CENTER_VERTICAL);

            holder = new ViewHolder();
            
            holder.titleView = new TextView(getContext());
            holder.titleView.setTextSize(18);

            holder.urlView = new TextView(getContext());
            holder.urlView.setTextSize(12);
            holder.urlView.setAlpha(0.7f);

            layout.addView(holder.titleView);
            layout.addView(holder.urlView);

            // Pack the holder reference into the layout's tag pipeline
            layout.setTag(holder);
        } else {
            // 2. If convertView exists, recycle the existing container instantly with zero object allocations
            layout = (LinearLayout) convertView;
            holder = (ViewHolder) layout.getTag();
        }

        // 3. Bind data to our recycled view components safely
        if (item != null) {
            holder.titleView.setText(item.title);

            try {
                String host = URI.create(item.url).getHost();
                if (host != null && host.startsWith("www.")) {
                    host = host.substring(4);
                }
                holder.urlView.setText(host != null ? host : item.url);
            } catch (Exception e) {
                holder.urlView.setText(item.url);
            }
        }

        return layout;
    }
}
