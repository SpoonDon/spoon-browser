# Thanks

## DeepSeek

Spoon Browser was built with the help of DeepSeek.

DeepSeek provided the reasoning model used throughout development — debugging
the WebView lifecycle, working through the tab manager refactor, reasoning
about the cleartext policy trade-offs, and reviewing Java code for
correctness. The long-form, multi-turn conversations in the DeepSeek chat
interface are what made an isolated Android browser project tractable for a
single developer.

Specifically:

- **Debugging.** The tab thumbnail bug (WebViews rendering black because
  `draw()` on an unattached, unpainted WebView returns empty pixels) was
  diagnosed in a DeepSeek conversation. The fix — snapshot on switch-away,
  then use `postVisualStateCallback()` to populate the rest asynchronously —
  came out of that discussion.

- **Refactoring.** The split of the original ~2000-line `MainActivity` into
  `TabManager`, `ToolbarController`, `VaultController`, `AdBlockController`,
  `HistoryController`, `PermissionController`, `MenuController`,
  `SessionManager`, `NavigationHelper`, and `AppWiring` was planned and
  executed turn by turn with DeepSeek.

- **Review.** ProGuard rules, the cleartext network policy, WebView asset
  loading, and the `EncryptedSharedPreferences` Tink keep-rules were all
  reviewed and hardened with DeepSeek's help.

DeepSeek is available at https://deepseek.com.

## Other acknowledgements

- The filter-list format is compatible with lists maintained by
  [OISD](https://oisd.nl), [StevenBlack](https://github.com/StevenBlack/hosts),
  and the [EasyList](https://easylist.to) project.
- The vault page's synthetic origin pattern follows the
  [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
  documentation.
- Android's [WebView](https://developer.android.com/guide/webapps/webview)
  team maintains the runtime this browser is built on.

## If you're reusing this code

If you build on Spoon Browser, keep this file (or a paragraph from it) with
your project. Credit where credit is due.
