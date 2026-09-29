import { CapacitorConfig } from '@capacitor/cli';

/**
 * Spoon Browser Capacitor configuration.
 *
 * Capacitor is used here only to manage the web-asset sync pipeline.
 * All actual browser logic lives in native Java (MainActivity.java etc.).
 *
 * The webDir points at the placeholder 'www/' directory so `npx cap sync`
 * has something to copy. When you add real web assets, drop them in www/.
 */
const config: CapacitorConfig = {
  appId: 'com.spoondon.browser',
  appName: 'Spoon Browser',
  webDir: 'www',

  android: {
    // We manage WebView security ourselves in Java; keep Capacitor's
    // defaults conservative.
    allowMixedContent: false,
    captureInput: false,
    webContentsDebuggingEnabled: false
  }
};

export default config;
