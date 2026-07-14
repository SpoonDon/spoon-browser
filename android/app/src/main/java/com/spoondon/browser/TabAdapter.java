package com.spoondon.browser;

import android.graphics.Bitmap;
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
        
        holder.txtTitle.setText(tab.getTitle());
        
        Bitmap thumbnail = tab.getThumbnail();
        if (thumbnail != null && !thumbnail.isRecycled()) {
            holder.imgThumbnail.setImageBitmap(thumbnail);
        } else {
            holder.imgThumbnail.setImageBitmap(null);
            holder.imgThumbnail.setBackgroundColor(0xFF000000); 
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTabSelected(holder.getAdapterPosition());
            }
        });

        holder.btnClose.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTabClosed(holder.getAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return tabList.size();
    }

    public void moveTab(int fromPosition, int toPosition) {
        // Safely remove and re-insert instead of swapping
        TabState movedTab = tabList.remove(fromPosition);
        tabList.add(toPosition, movedTab);
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
