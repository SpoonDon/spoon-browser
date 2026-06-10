package com.spoondon.browser;

import android.content.Context;
import android.widget.ArrayAdapter;

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
}
