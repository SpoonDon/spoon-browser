package com.spoondon.browser;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {
    private final List<WebViewTab> tabs;
    private final OnTabClickListener listener;

    public interface OnTabClickListener {
        void onTabSelected(int position);
        void onTabClosed(int position);
    }

    public TabAdapter(List<WebViewTab> tabs, OnTabClickListener listener) {
        this.tabs = tabs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tab, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        WebViewTab tab = tabs.get(position);
        holder.titleText.setText(tab.getTitle());

        // Bitmap Recycling Logic
        if (holder.imgThumbnail.getDrawable() != null) {
            Bitmap oldBitmap = ((BitmapDrawable) holder.imgThumbnail.getDrawable()).getBitmap();
            if (oldBitmap != null && !oldBitmap.isRecycled()) {
                // Release reference to prevent memory leaks during recycling
                holder.imgThumbnail.setImageBitmap(null);
                // oldBitmap is now eligible for GC since no views hold it
            }
        }

        if (tab.getThumbnail() != null) {
            holder.imgThumbnail.setImageBitmap(tab.getThumbnail());
        } else {
            holder.imgThumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        holder.closeBtn.setOnClickListener(v -> {
            if (listener != null) listener.onTabClosed(position);
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(position);
        });
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    static class TabViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView titleText;
        View closeBtn;

        public TabViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            titleText = itemView.findViewById(R.id.titleText);
            closeBtn = itemView.findViewById(R.id.closeBtn);
        }
    }
}
