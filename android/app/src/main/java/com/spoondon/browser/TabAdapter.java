package com.spoondon.browser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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

    // Letter-tile placeholder dimensions. Scaled by ImageView at bind time;
    // a portrait phone aspect keeps the placeholder from looking stretched
    // even if the tab card frame is a different shape.
    private static final int PLACEHOLDER_W = 720;
    private static final int PLACEHOLDER_H = 1280;
    private static final int PLACEHOLDER_BG = 0xFF1A1A1A;
    private static final int PLACEHOLDER_FG = 0xFF4D6BFE;

    private final List<TabState> tabList;
    private final OnTabActionListener listener;

    public TabAdapter(List<TabState> tabList, OnTabActionListener listener) {
        this.tabList = tabList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tab_card, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        TabState tab = tabList.get(position);

        String dynamicTitle = "[" + (position + 1) + "/" + tabList.size() + "] "
                + (tab.getTitle() != null ? tab.getTitle() : "New Tab");
        holder.txtTitle.setText(dynamicTitle);

        Bitmap thumbnail = tab.getThumbnail();
        if (thumbnail != null && !thumbnail.isRecycled()) {
            holder.imgThumbnail.setImageBitmap(thumbnail);
        } else {
            // No snapshot yet - this tab has never been foregrounded (or
            // was restored from session before its first frame painted).
            // Show a letter tile instead of pure black, matching the home
            // grid's letter-tile fallback aesthetic.
            holder.imgThumbnail.setImageBitmap(buildLetterTile(tab.getTitle()));
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

    /**
     * Build a small neutral tile with the first letter of the tab title.
     * Used when no thumbnail has been captured yet. Cheap - a few hundred
     * microseconds on a modern device, and only fires for the handful of
     * rows visible in the ViewPager2 window.
     */
    @NonNull
    private Bitmap buildLetterTile(String title) {
        Bitmap bmp = Bitmap.createBitmap(PLACEHOLDER_W, PLACEHOLDER_H,
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(PLACEHOLDER_BG);

        String letter = "?";
        if (title != null && !title.isEmpty()) {
            char ch = title.charAt(0);
            if (Character.isLetterOrDigit(ch)) {
                letter = String.valueOf(Character.toUpperCase(ch));
            }
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(PLACEHOLDER_FG);
        p.setTextSize(PLACEHOLDER_W * 0.35f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);

        Paint.FontMetrics fm = p.getFontMetrics();
        float baselineY = PLACEHOLDER_H / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(letter, PLACEHOLDER_W / 2f, baselineY, p);

        return bmp;
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
