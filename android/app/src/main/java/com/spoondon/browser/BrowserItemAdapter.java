package com.spoondon.browser;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

public class BrowserItemAdapter extends ArrayAdapter<BrowserItem> {

    // Cache the padding calculations so we don't query device metrics on the fly
    private final int padX;
    private final int padY;

    private static class ViewHolder {
        TextView titleView;
        TextView urlView;
    }

    public BrowserItemAdapter(Context context, ArrayList<BrowserItem> items) {
        super(context, 0, items);
        
        // Calculate screen density precisely once when the adapter boots up
        float density = context.getResources().getDisplayMetrics().density;
        this.padX = (int) (16 * density); 
        this.padY = (int) (12 * density); 
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        BrowserItem item = getItem(position);
        LinearLayout layout;
        ViewHolder holder;

        if (convertView == null) {
            layout = new LinearLayout(getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(padX, padY, padX, padY); // Use pre-calculated padding
            layout.setGravity(Gravity.CENTER_VERTICAL);

            holder = new ViewHolder();
            
            holder.titleView = new TextView(getContext());
            holder.titleView.setTextSize(18);

            holder.urlView = new TextView(getContext());
            holder.urlView.setTextSize(12);
            holder.urlView.setAlpha(0.7f);

            layout.addView(holder.titleView);
            layout.addView(holder.urlView);

            layout.setTag(holder);
        } else {
            layout = (LinearLayout) convertView;
            holder = (ViewHolder) layout.getTag();
        }

        if (item != null) {
            holder.titleView.setText(item.title);
            holder.urlView.setText(item.displayHost); // Instantly bind the pre-computed string
        }

        return layout;
    }
}
