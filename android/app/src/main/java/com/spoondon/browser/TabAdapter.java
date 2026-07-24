package com.spoondon.browser;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {

    public interface OnTabActionListener {
        void onTabSelected(int position);
        void onTabClosed(int position);
    }

    private final List<TabState> tabList;
    private final OnTabActionListener listener;

    public TabAdapter(List<TabState> tabList, OnTabActionListener listener) {
        this.tabList = tabList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tab_card, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        TabState tab = tabList.get(position);
        
        String dynamicTitle = "[" + (position + 1) + "/" + tabList.size() + "] " + tab.getTitle();
        holder.txtTitle.setText(dynamicTitle);

        Bitmap oldBitmap = holder.imgThumbnail.getDrawable() != null    
            ? ((BitmapDrawable) holder.imgThumbnail.getDrawable()).getBitmap()     
            : null;
        if (oldBitmap != null && !oldBitmap.isRecycled()) {            
        }
        holder.imgThumbnail.setImageBitmap(null);
        
        Bitmap thumbnail = tab.getThumbnail();
        if (thumbnail != null && !thumbnail.isRecycled()) {
            holder.imgThumbnail.setImageBitmap(thumbnail);
        } else {
            holder.imgThumbnail.setImageBitmap(null);
            holder.imgThumbnail.setBackgroundColor(0xFF000000); 
        }

        holder.itemView.setOnClickListener(v -> {
            int currentPos = holder.getAdapterPosition();
            if (listener != null && currentPos != RecyclerView.NO_POSITION) {
                listener.onTabSelected(currentPos);
            }
        });

        holder.btnClose.setOnClickListener(v -> {
            int currentPos = holder.getAdapterPosition();
            if (listener != null && currentPos != RecyclerView.NO_POSITION) {
                listener.onTabClosed(currentPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tabList.size();
    }

    public void moveTab(int fromPosition, int toPosition) {
        if (fromPosition < 0 || toPosition < 0 || 
            fromPosition >= tabList.size() || toPosition >= tabList.size()) {
            return;
        }

        java.util.Collections.swap(tabList, fromPosition, toPosition);
        notifyItemMoved(fromPosition, toPosition);
    }

    public static class TabViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView txtTitle;
        ImageButton btnClose;

        public TabViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgTabThumbnail);
            txtTitle = itemView.findViewById(R.id.txtTabTitle);
            btnClose = itemView.findViewById(R.id.btnCloseTab);
        }
    }
}
