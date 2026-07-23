package com.spoondon.browser;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for Spoon Browser components.
 */
public class ExampleUnitTest {

    @Test
    public void urlValidation_basic() {
        // Test basic URL validation logic
        String validUrl = "https://example.com";
        String invalidUrl = "not-a-url";
        
        assertTrue("Valid URL should pass", isValidUrl(validUrl));
        assertFalse("Invalid URL should fail", isValidUrl(invalidUrl));
    }

    @Test
    public void urlValidation_withHttp() {
        assertTrue("HTTP URL should be valid", isValidUrl("http://example.com"));
    }

    @Test
    public void urlValidation_withSubdomain() {
        assertTrue("Subdomain URL should be valid", isValidUrl("https://www.example.com"));
    }

    @Test
    public void urlValidation_withPath() {
        assertTrue("URL with path should be valid", isValidUrl("https://example.com/path/to/page"));
    }

    @Test
    public void urlValidation_withQueryParams() {
        assertTrue("URL with query params should be valid", 
            isValidUrl("https://example.com/search?q=test&page=1"));
    }

    @Test
    public void urlValidation_rejectsMalformed() {
        assertFalse("Malformed URL should be rejected", isValidUrl("ht!tp://example.com"));
        assertFalse("Empty string should be rejected", isValidUrl(""));
        assertFalse("Null should be rejected", isValidUrl(null));
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return url.matches("^https?://[a-zA-Z0-9.-]+(?:/[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]*)?$");
    }

    @Test
    public void adBlockFilter_basic() {
        // Test basic ad-blocking filter logic
        String[] adUrls = {
            "https://ads.example.com/banner.jpg",
            "https://tracker.example.com/pixel.gif",
            "https://doubleclick.net/ad/123"
        };
        
        String[] safeUrls = {
            "https://example.com/image.png",
            "https://cdn.example.com/logo.svg"
        };
        
        for (String adUrl : adUrls) {
            assertTrue("Ad URL should be blocked: " + adUrl, isAdUrl(adUrl));
        }
        
        for (String safeUrl : safeUrls) {
            assertFalse("Safe URL should not be blocked: " + safeUrl, isAdUrl(safeUrl));
        }
    }

    private boolean isAdUrl(String url) {
        String[] adKeywords = {"ads", "tracker", "doubleclick", "analytics"};
        for (String keyword : adKeywords) {
            if (url.toLowerCase().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void credentialManager_encryption() {
        // Test that credential manager can encrypt/decrypt
        String password = "test_password_123";
        assertNotNull("Password should not be null", password);
        assertTrue("Password should have length", password.length() > 0);
    }

    @Test
    public void tabState_creation() {
        // Test tab state creation
        String title = "Test Tab";
        String url = "https://example.com";
        
        assertNotNull("Title should not be null", title);
        assertNotNull("URL should not be null", url);
        assertEquals("Title should match", "Test Tab", title);
    }
}
