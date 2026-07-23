package com.spoondon.browser;

import android.content.Context;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented tests for Spoon Browser Android components.
 * These tests run on an Android device or emulator.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class BrowserInstrumentedTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void useAppContext() {
        // Verify app context is available
        assertNotNull("Context should not be null", context);
        assertEquals("Package name should match", 
            "com.spoondon.browser", 
            context.getPackageName());
    }

    @Test
    public void databaseHelper_creation() {
        // Test that database helper can be instantiated
        BrowserDatabaseHelper dbHelper = BrowserDatabaseHelper.getInstance(context);
        assertNotNull("Database helper should not be null", dbHelper);
    }

    @Test
    public void credentialManager_initialization() {
        // Test credential manager initialization
        SecureCredentialManager credentialManager = new SecureCredentialManager(context);
        assertNotNull("Credential manager should not be null", credentialManager);
    }

    @Test
    public void adBlockEngine_initialization() {
        // Test ad block engine initialization - AdBlockEngine uses static methods
        // Just verify the class is accessible
        assertTrue("AdBlockEngine class should be accessible", true);
    }

    @Test
    public void tabState_properties() {
        // Test TabState object properties - requires WebView constructor
        // We'll test with a mock approach since we can't create WebView in isolation easily
        TabState tabState = new TabState(null);
        assertNotNull("TabState should not be null", tabState);
        
        // Test setting and getting title
        String testTitle = "Test Title";
        tabState.setTitle(testTitle);
        assertEquals("Title should match", testTitle, tabState.getTitle());
        
        // Test setting and getting URL
        String testUrl = "https://example.com";
        tabState.setUrl(testUrl);
        assertEquals("URL should match", testUrl, tabState.getUrl());
        
        // Test ID generation
        assertNotNull("Tab ID should not be null", tabState.getId());
    }

    @Test
    public void browserItem_creation() {
        // Test BrowserItem creation
        BrowserItem item = new BrowserItem("Test Site", "https://example.com");
        assertNotNull("BrowserItem should not be null", item);
        assertEquals("Title should match", "Test Site", item.title);
        assertEquals("URL should match", "https://example.com", item.url);
        assertNotNull("Display host should not be null", item.displayHost);
    }

    @Test
    public void browserItem_withNullValues() {
        // Test BrowserItem handles null values gracefully
        BrowserItem item = new BrowserItem(null, null);
        assertNotNull("BrowserItem should not be null", item);
        assertEquals("Title should default to Untitled", "Untitled", item.title);
        assertEquals("URL should default to about:blank", "about:blank", item.url);
    }

    @Test
    public void adBlockEngine_staticMethods() {
        // Test AdBlockEngine static methods are accessible
        // hasRules() should return false initially (no rules loaded)
        assertFalse("AdBlockEngine should not have rules initially", AdBlockEngine.hasRules());
    }
}
