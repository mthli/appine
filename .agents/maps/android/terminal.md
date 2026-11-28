# android/terminal Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/terminal.md` for the paired decision history (it still holds the pre-split decisions for `android/vt`, `android/vtcore`, `android/vtview`, `android/vtgestures`, `android/vtrender`, `android/privacy`, `android/licenses`, `android/tabbar`, `android/keys` and `android/file-import`).
>
> Verified: 2026-11-26 (full refresh: 18 adversarial claims checked independently — 17 confirmed and 1 partial corrected, plus 5 confirmed bugs/debt items recorded below)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: port mapping row order)

## Responsibilities

`android/terminal` owns the Android UI around the foreground service's sessions. It selects and closes tabs, connects the terminal and controls, and handles search, clipboard questions, links, imports, menu actions, insets and the IME.

Under `android/app/src/main/java/io/github/mthli/appine/` it owns `MainActivity.kt`, `TerminalLayout.kt`, `TerminalLinks.kt`, `NotificationPermission.kt`, `BatteryOptimization.kt`, `SearchBarView.kt` and `Ui.kt`. It also owns `android/app/src/main/res/` except for `android/privacy`'s `layout/privacy_shield.xml`, `drawable/privacy_emblem.xml` and `drawable/ic_lock.xml`, and `android/licenses`' `layout/list_item.xml` and `values/ids.xml`. The manifest that consumes these resources belongs to `android/build`.

## Key types

| Type | Role |
| --- | --- |
| `MainActivity` | A `FragmentActivity`, `TerminalService.Listener` and `Privacy.Listener`. Holds the binding, local selection and index, requested tab ID, Back callbacks, dialogs and latest toast. |
| `Host` | Implements `VtView.Host` for grid, input, addressed focus events, zoom, modifiers, links and search counts. |
| `Bar` / `Tabs` | Activity adapters for numbered-bar actions and title-list actions on a specific session. |
| `Find` / `Keys` | Connect search actions to `VtView` and extra keys to the focused input. |
| `TerminalLayout` | Owns the view tree, status display, insets and IME animation. |
| `SearchBarView` | Owns query, count and search controls. Its `Target` accepts `search(needle)`, `step(older)` and `close()`. |
| `SearchExtraKeys` | Routes extra-key input and maps a `SearchEditState` to a `SearchExtraKeyResult` (close, step, edit or consume). The view applies edits to the field. |
| `TerminalLinks` | Routes URIs for one activity and shares its toast callback. |
| `NotificationPermission` / `BatteryOptimization` | Supply availability checks and system navigation to the terminal and Settings. |
| `Ui.kt` extensions | Shared sizes, colors, drawables, package intents and dialog helpers. |

## Public entry points

- The exported `MainActivity` is the `MAIN` / `LAUNCHER` target, with `singleTask`, `adjustResize` and `Theme.Appine.Terminal`. A tab notification supplies `MainActivity.EXTRA_TAB` (key `tab`, integer `VtSession.id`). Existing activities receive `onNewIntent`.
- The service calls `onProgress`, `onMachineReady`, `onSessionsChanged`, `onTabStatus`, `onBell`, `onClipboardRead`, `onOpenUrl`, `onSettingsChanged`, `onNotice`, `onRestarting` and `onExitAll` on the main thread. Clipboard and URL callbacks report acceptance.
- `onPrivacyUnlocked` retries import delivery, notices and clipboard reads. Activity-result callbacks accept picked URIs or refresh notification permission state. View targets, `dispatchKeyEvent` and the two AndroidX Back callbacks handle input.

## Data flow / lifecycle

### Launch, configuration and binding

`attachBaseContext` calls its superclass and applies a `Configuration` containing only `UI_MODE_NIGHT_YES`, before the window or dynamic colors access resources. Framework selection menus then see night resources. The manifest handles orientation, screen size, smallest screen size, screen layout, keyboard, keyboard visibility, navigation and UI mode in place. Locale, density and font scale use normal recreation. Settings and license pages follow system day/night changes.

`onCreate` applies dynamic colors when available before calling the superclass. It installs Back callbacks, initializes notification request history and restores Keep screen on. `savedFontSize` migrates an integer pixel value in `font_size` to float `font_size_dp` at current density, removing the old key. It passes the saved size (default 12 dp) to the view in pixels.

Only a fresh creation with no saved state and no `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` reads the tab ID. Recreation or Recents can reuse the original notification intent. `onNewIntent` calls `setIntent`, replaces `requestedTab` (default −1) and tries to show it immediately.

The activity assigns `TerminalLayout`, updates the banners and initial bar, disables decor fitting, installs the root and observes the import model. Status initially reads “Starting…”. Each `onStart` applies `Settings.fillWide` and `Settings.showTabList`, starts the foreground service and binds with `BIND_AUTO_CREATE`. A live settings change applies the same display preferences.

On connection it takes the service from `LocalBinder` and registers the listener. A nonzero machine or recorded start error replays `onMachineReady`, otherwise available progress replays `onProgress`. It then tries `onNotice`. Progress and start failure hide the terminal view and display status. Success reveals the terminal and selects a session. An unlocked activity takes a queued notice and shows a protected dialog.

Resume refreshes both banners and posts a clipboard retry so the lifecycle can reach `RESUMED`. Pause closes the title list. A privacy-mode pause also cancels the activity toast and denies an open clipboard question. Stop denies an open clipboard question, unregisters, unbinds and clears the service reference. Local selection and attachment survive stop. Destroy dismisses the import dialog with its dismiss listener removed and calls `layout.screen.attach(null)`.

### Selecting and showing a session

`showRequestedTab` retains a nonnegative request until a bound service has a nonzero machine. It then looks up the ID and consumes the request even on a miss. On successful readiness it chooses the first available candidate.

1. The live session named by the request.
2. This activity's `current`, if still in the service's sessions.
3. The service's `current`, if still present (including after activity recreation).
4. The service's `focus` from restored tabs, if still present.
5. The last session. With an empty list, create a tab.

`newSession` requires a running machine. It passes `current?.cwd()` (the console foreground group's working directory when available) to `createSession`. A null directory selects the login home. `Host.onGrid` starts the current shell once the view has a grid.

`show` normally closes the list and always closes visible search, including on reselection after a rebind. It assigns local `current`, jumps the progress line to that session, sets service `current` (canceling its notification), attaches and focuses the view, renders the controls and retries clipboard delivery. Changing the local session identity first settles its open clipboard question.

`renderTabs` snapshots live sessions, records the shown session's index, updates the bar and an open list, and refreshes the import button. `onTabStatus` refreshes status marks and updates the current progress line without a jump. Foreground bells go to `VtView.bell`. Restart closes list and search, clears current and progress, detaches the view and shows “Restarting…”. `onExitAll` finishes the activity.

### Closing tabs and choosing the successor

The bar's ×, row close, Ctrl+Shift+W and close-other/all menu actions use `confirmClose`. No busy target means immediate removal. Any busy target prompts once for the full set, with a singular title or localized quantity. Confirmation filters out sessions removed while the dialog was open. Dismissal requests terminal focus.

`VtSession.busy` is false for an ended shell or a session without a native terminal. The native predicate recognizes prompt marks and, on the primary screen under its fallback conditions, an idle foreground interactive shell even without marks.

Bulk close removes current last. Each `removeSession` releases the session and can synchronously trigger `onSessionsChanged`. This ordering avoids attaching and starting another tab just to close it. If current disappears, its last rendered index clamped to the new last index selects the following tab or the preceding last tab. This replacement can preserve an open list. An empty list with a nonzero machine finishes the activity. The service manages its own shutdown.

### Tab list, overflow menu and Back

Opening the title list requires an unlocked activity and a nonempty session snapshot. `TabListView.open` reveals the current row and preserves terminal input focus. The rows occupy at most ten row heights within the viewport. Taps outside the rows dismiss the overlay, including taps over the keys. Row select/close rechecks the bound session's live membership and the privacy gate. Row close can leave the list open during confirmation and successor selection.

`updateTabListControls` sets the covered column to `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` and updates the tab bar's list-open state. Opening enables `closeTabListOnBack`. Closing clears the list's row/session references, restores `IMPORTANT_FOR_ACCESSIBILITY_AUTO`, resets the bar state and disables the callback. List dismissal requests terminal focus only if the list held it.

Back, Escape, the list toggle and an outside tap dismiss the list. Normal tab selection, search, the menu, bar close/import, extra-key dispatch, pause, restart and disabling the list control can also close it. Escape's matching release is consumed.

The overflow uses an end-aligned AppCompat `PopupMenu` styled with `Widget.Appine.MorePopup`. Find requires a current tab. Reset requires a native terminal, and Clear screen additionally requires the primary screen. Close other/all tabs capture the respective session sets when the menu opens. Port mappings opens a private, scrollable dialog with a fresh service snapshot. Each row shows the protocol followed by the Linux port → phone port mapping. Empty and unavailable results have distinct messages. Stop and kernel restart dismiss the dialog. Settings opens `android/settings`' `SettingsActivity`.

Keep screen on is checkable even without a tab. `ThemeOverlay.Appine.CheckPopup` makes the checkbox background transparent, leaving press feedback to the row. Tapping updates the check, sets or clears `FLAG_KEEP_SCREEN_ON` and stores `keep_screen_on` in activity-private preferences (default off). Only the visible terminal window requests this behavior. The service's screen-off policy and wake lock are independent.

### Permission banners and Settings actions

Each banner is a 32 dp row with a weighted, start-aligned action and a square × with a localized description. The 14 dp message has 10 dp horizontal padding and end ellipsis. Both buttons request `KEYBOARD_TAP`. The rows can remain visible during search. An unlocked dismissal persists its own flag, `dismissed_notification_prompt` or `dismissed_battery_prompt`. Settings rows follow live system state independently.

#### Notifications

The banner appears when `NotificationManager.areNotificationsEnabled()` is false and its dismissal flag is clear. Individual disabled channels do not trigger it. Both callers initialize `requested_notifications` in the named `MainActivity` preference file. If absent, it starts true on an updated installation (`lastUpdateTime > firstInstallTime`) and false otherwise.

Only an explicit tap calls `NotificationPermission.enable`. It returns if locked or already enabled. On Android 13+ it requests `POST_NOTIFICATIONS` only when permission is missing, no attempt is recorded and Android reports no rationale. It records the attempt before launch, so dismissing the system dialog counts. Other disabled cases, including Android 11–12, open `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`. A missing handler opens package details instead. Permission results and resume refresh visibility. Banner dismissal leaves the request history intact.

#### Battery usage

The battery banner requires a clear dismissal flag and a false result from `PowerManager.isIgnoringBatteryOptimizations(packageName)`. An unlocked tap rechecks exemption, then opens `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the package URI. A missing handler falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, the Settings row's direct destination. Both battery routes guard against lock or an existing exemption. Resume rereads the system state.

### Search and extra-key focus

Find or Ctrl+Shift+F with a current session calls `openSearch`. It closes the list, cancels held extra-key presses, hides the tab bar and opens the equal-height search bar, preserving terminal rows. The single-line field takes focus, selects its retained query, posts an IME show request and searches again. It disables suggestions, extract UI and fullscreen IME editing.

Each edit calls `Find.search` and `VtView.search`. Frames report counts through `Host.onSearchCount(total, selected)`. An empty query hides the count. Otherwise it reads “selected/total”, counting from the newest (1), or 0 before selection. No matches reads “0/0”. The on-screen and extra-key arrows step older or newer, selecting the newest on the first older step. In the field, hardware Enter and numpad Enter step older, Shift reverses their direction, and IME Search steps older. Steps wrap and reveal the match. Up, down and × clicks request tap haptics. Hardware and IME actions invoke the target directly.

`Keys.key` and `Keys.text` route through `SearchExtraKeys`. A locked event returns before dispatch. Otherwise routing closes the tab list. If `SearchBarView.hasInputFocus` (visible and focused), the bar handles the event and the router calls the panel's `modifiersUsed` even for ignored keys. This clears eligible one-shots and preserves locked modifiers. When the terminal regains focus with search still open, extra keys resume calling `VtView.press` / `type`. The view reads modifiers through `Host`.

In the focused bar, extra Escape closes even with Ctrl or Alt. Other Ctrl/Alt combinations and terminal-only keys are consumed. Up/down step matches. Left/right collapse or move the selection, Home/End move to the ends and Shift extends from the anchor. The edit rules support backward deletion. Extra text drops CR/LF and replaces the selection or inserts at the caret.

×, Escape in the field and the enabled `closeSearchOnBack` callback call `closeSearch`. Back reaches the activity when the IME yields it. Closing cancels extra-key repeats, ends search and its marks, restores the tab bar and terminal focus, and disables the callback. Showing any tab or restarting also closes search. The field retains its text for the next search in this activity.

### Hardware shortcuts and volume keys

`dispatchKeyEvent` consumes events while locked. Otherwise it handles list Escape, then checks key downs for a shortcut or volume switch. A taken down records its key code in `shortcutKey`. An up whose key code equals `shortcutKey` clears the slot and is swallowed before the view can encode a release; because `shortcutKey` uses `0` as its empty sentinel, an `ACTION_UP` with `KEYCODE_UNKNOWN` is also swallowed when no shortcut down is active. Unhandled events go to superclass dispatch, including events directed to the focused search field. Shortcuts use the event's actual modifier bits.

| Key down | Activity behavior |
| --- | --- |
| Ctrl+Shift+T / W / F | Open a tab, run current-tab close confirmation, or open Find on the first down. Repeats are consumed. |
| Ctrl+Tab / Ctrl+Shift+Tab | Select next / previous with wraparound. Holding the keys repeats. The chord is consumed even when there is no other tab. |
| Ctrl+Shift+Up / Down | Ask `VtView.jumpToPrompt` to move backward / forward, including repeats. It consumes the jump on a primary native terminal even with no prompt mark. On the alternate screen or with no native terminal it returns false and normal dispatch continues. |
| Volume up / down | Select previous / next with wraparound if the setting is enabled and there is another tab. A successful first down requests `KEYBOARD_TAP` on the terminal view. |

The Ctrl shortcuts reject Alt and Meta. `Settings.volumeKeysSwitchTabs` defaults on and is read on each initial volume-key press. `switchBy` requires the current session to be in a list of at least two sessions. Volume repeats are consumed only for the remembered key and never switch again. With the setting off or no eligible switch, the initial press falls through for normal system volume handling. The system shortcut list includes the tab actions, Find and the two prompt jumps.

### Window layout and IME animation

The black vertical root holds a top frame for the tab and search bars, then a weighted overlay frame. Its first child stacks the notification banner, battery banner, weighted terminal body and extra keys in that order. The body layers `VtView`, centered status text and a top-aligned `ProgressLine` of height `line()`. The overlay's second child is the full-size `TabListView`. List rows thus cover banners, terminal and keys.

With decor fitting disabled, a root insets listener obtains the combined system-bar, display-cutout and IME insets, pads all four sides and consumes the insets. Side padding matters for a landscape cutout. `clipToPadding = false` lets keys draw into the root's bottom padding. Both the overlay frame and the content column set `clipChildren = false` so the keys can cross their bottom edges during animation.

`keysFollowIme` tracks the particular IME animation. In `onPrepare` it records the drawn bottom, `keys.bottom + keys.translationY`. After layout takes the end insets, `onStart` sets translation to the difference between that recorded position and the new bottom. `onProgress` multiplies the difference by the remaining interpolated fraction. Only the followed animation's `onEnd` clears the translation. An interruption therefore starts from where the keys are drawn and a late end from the old animation cannot snap them back. The effect moves the keys across the terminal's end layout without resizing it on each animation frame.

### Input, focus and the clipboard

`Host.onInput` checks privacy at the final write, including delayed IME or paste input. It writes to the current running session. For an ended session any buffer containing CR or LF closes the tab directly. `Host.onFocus(session, data)` instead takes its recipient from `VtView.sendFocus`. Mode-1004 focus-out can reach the outgoing session after the activity has selected its successor. The host writes only when unlocked and that supplied session is running. `Host.onZoom` saves the new pixel size divided by density.

OSC 52 reads wait in a session's `ClipboardReads`. On the first waiting read the service offers the batch to listeners if the session is service-current, a terminal activity is registered and privacy is unlocked. An unclaimed batch can wait 60 seconds. Up to 64 targets share it, with overflow answered empty.

1. `onClipboardRead(sessionId)` requires both the local current ID and identity with service `current`. An existing dialog returns the session's `promptActive` without rereading. Otherwise `beginPrompt` claims a pending batch only with no active prompt, a resumed activity, window focus and an unlocked gate. Claiming cancels the waiting deadline.
2. The activity reads only the first item with `coerceToText(this)`. No clip, no items or an empty coerced value gets an immediate `answerClipboardRead("")`. A non-text item that coerces to nonempty text goes to the question. There is no MIME filter.
3. One `showPrivate` dialog previews the captured value, truncating beyond 300 Kotlin `Char` units and appending an ellipsis. Allow checks resumed and unlocked at the tap. On dismissal, only `clipboardQuestion === dialog` may clear the field and answer. If Allow succeeded and both checks still pass, the session gets the full captured text. Denial, cancellation or a failed check returns null, encoded as an empty reply. A matching dismissal refocuses the terminal and retries delivery.
4. On a change of current identity, stop or privacy-mode pause, `dismissClipboardQuestion` calls the old session's `clipboardReads.denyPrompt()` only for an open question. The claim's dismiss closure clears the activity field before dismissing the window, so its queued listener cannot answer a replacement. Session reset, exit or release can clear the batch and close its preview without answering.
5. `deliverClipboardRead` retries after showing a session, posted resume, window focus gain, privacy unlock and matching dialog dismissal. Switching away leaves an unclaimed batch subject to its session deadline.

### Terminal links and guest browser requests

`Host.onLink` hands a URI from the terminal's link gestures or selection menu to `TerminalLinks.open`. Scheme comparisons for HTTP, HTTPS and file ignore case. Web links use an unpinned AndroidX Custom Tab with the URL as its data, `CATEGORY_BROWSABLE` and its start animation bundle. Other non-file schemes use a browsable `ACTION_VIEW`. Both call `startViewer`, which checks privacy immediately before launch. It returns true on accepted `startActivity`, or shows the missing-app toast and returns false on `ActivityNotFoundException`.

A `file:` link starts an `appine-link` thread for `Documents.fileLink`. The provider resolves the host, document and visibility. On the UI thread the activity drops the result if destroyed or locked. A found document opens via `ACTION_VIEW` with the provider URI, MIME type and read grant. Remote host and missing file each get a short toast. A hidden file gets a longer hint about the file-access setting.

A guest browser request arrives separately through `TerminalService.Listener.onOpenUrl` after `BrowserBridge` validates the web URL. The activity requires both window focus and an unlocked gate and returns the result of `openBrowser` to the service. Success acknowledges Android accepting the launch.

### File picker, cancellation and results

`MainActivity` observes `TerminalFiles` from `ViewModelProvider`. `updateImport` first calls `tabBar.setImportState`. A new import requires a nonzero machine and `!occupied`. The model is occupied while selecting, holding picked URIs, copying or retaining a result. During copying, the button stays enabled, changes from @ to ■ and announces cancellation.

`Bar.importFiles` closes the list. The handler checks privacy, then cancels an active batch before checking machine readiness. Otherwise it captures `Settings.login` with `begin` and launches `OpenMultipleDocuments` for all MIME types. A missing picker clears selection with an empty list and shows a long toast. Picker results go to `selected`. Files go to the resolved login home regardless of the current tab's working directory.

After updating the button, `updateImport` requires a resumed, unlocked activity to call `importSelected` or show a result. Starting a copy shows a toast. Model changes, rendering tabs and privacy unlock can trigger delivery. One protected dialog displays successful paths in monospace on separate lines, then any failures. Copy paths appears only for successes and rechecks privacy before writing `TerminalFiles.clipboardPaths` to the clipboard as space-separated, shell-escaped arguments without a trailing newline.

Ordinary dismissal clears the retained result and refocuses the terminal. Destroy removes the dismiss listener before closing the dialog, allowing the replacement activity to present the result after recreation.

### Shared UI and resources

- `LINE_DP` is 1. `Context.dp` multiplies by display density and truncates to integer pixels. `line()` applies a minimum of one pixel for indicators and progress. The `View` versions delegate to the context. `themeColor` falls back to white, `themeDrawable` returns a nullable drawable and recycles its attribute array, and `touchFeedback` reads the framework `selectableItemBackground`.
- `terminalControlColor(true)` blends primary with 25% white. The ordinary color blends on-surface-variant with 40% black. Tab controls and extra keys use these colors against black. Search controls, banners and status text read their own theme attributes.
- `openPackageSettings` builds a settings intent with a `package:` URI. `showPrivate` creates a dialog, registers it with `Privacy.protect` and then shows it. `monospaceMessage` applies a `TypefaceSpan` only to the supplied value and expands a localized `^1` template with `TextUtils.expandTemplate`. Import results and unsafe-paste previews use it.
- `values/themes.xml` defines Material 3 `Theme.Appine` (DayNight, surface window background) and `Theme.Appine.Terminal` (Dark, black window and system bars, fallback line spacing disabled). Both have no action bar. `Widget.Appine.MorePopup` offsets the overflow horizontally by −3.5 dp and vertically by 4 dp. `ThemeOverlay.Appine.CheckPopup` supplies the transparent control background and framework checkbox style.
- `layout/page.xml` supplies Settings and the two license pages with a coordinator root, app bar, toolbar with Up affordance and scrolling content frame. `layout/preference_switch.xml` exposes a `MaterialSwitch` at the standard `switchWidget` ID. Its background is null and it leaves click and focus handling to the preference row.
- The adaptive launcher icon references separate background, foreground and monochrome drawables. `values/colors.xml` supplies the foreground stroke color. `ic_notification` is the shared small notification icon. `xml/diagnostic_paths.xml` exposes the `diagnostic-exports/` cache subdirectory through the manifest's diagnostic provider. `xml/data_extraction_rules.xml` excludes root, files, database, shared preferences and external data for cloud backup and device transfer. The application also declares backup and full backup disabled.
- `xml/locale_config.xml` advertises English, Simplified Chinese, Traditional Chinese, Korean and Japanese to Android's per-app language picker on Android 13+. Their tags are `en`, `zh-CN`, `zh-Hant`, `ko` and `ja`. English lives in `values`, with translations in `values-zh-rCN`, `values-b+zh+Hant`, `values-ko` and `values-ja`. Older supported releases follow the system language. The strings files follow the same feature order across terminal controls, clipboard, links, import, Settings, privacy, licensing, notifications, restoration and reports. Translations omit non-translatable names/templates and use the `other` plural form.

## Dependencies (inbound / outbound)

### Inbound

- `android/build` declares the activity, theme and XML configuration. `android/reports` supplies the tab notification intent. The service's foreground notification opens the activity without a requested tab.
- `android/service` calls the listener. `android/privacy` signals unlock on a resumed page. `android/vtview` calls the host, including links from `android/vtgestures`. `android/tabbar` and `android/keys` call the activity's adapters.
- `android/settings` uses the permission helpers, preference switch, page frame and dialog helpers. `android/licenses` shares the page frame.

### Outbound

- `android/service` owns the machine, sessions, current/restored focus, settings and notices. `android/vt` supplies session state, working directory, grid startup, writes, busy status, progress, clear/reset and clipboard batches.
- `android/vtview` owns attachment, encoding, IME and paste, search, prompt jumps and bell feedback. Link detection belongs to `android/vtgestures`, native terminal state to `android/vtcore`, and frame data and cell highlights to `android/vtrender`.
- `android/tabbar` renders tabs, list, import/stop button and progress. `android/keys` owns key rows, repeats and modifier lifetimes. `android/file-import` owns requests, copying, results and path escaping. `android/documents` resolves guest files.
- `android/privacy` guards UI operations and protects dialog windows. `android/settings` supplies the Settings destination. AndroidX, Material and platform services supply Back, activity results, lifecycle, insets, dialogs, Custom Tabs, clipboard, IME and intent dispatch.

## Invariants and gotchas

- Apply dynamic colors before building views and assign the layout before attaching its root. Grid, input and search callbacks use the activity's layout.
- Tab IDs cross intents, while live list actions and focus bytes carry session objects. The cached index is for finding a successor after removal.
- Window focus gates a clipboard claim. An open preview can hold focus itself. Settling its batch depends on dialog identity and authorization at dismissal.
- Dialog-window protection and operation authorization are separate checks. Keep the privacy check at the final input, clipboard, import and intent operations. New activity toasts replace the previous one and are suppressed while locked.
- Resume and unlock gate queued import startup and presentation. An active worker belongs to the retained model. A completed file-link lookup checks activity destruction and lock state regardless of which tab now has focus.

## Confirmed bugs / technical debt

- `MainActivity.confirmClose` has a stale comment claiming every running shell without prompt marks is busy. The native busy predicate can identify an idle foreground interactive shell.
- `shortcutKey` stores only the last intercepted key code and uses `0` (`KeyEvent.KEYCODE_UNKNOWN`) as its empty sentinel: two overlapping, different intercepted downs can overwrite the first code before its up arrives (letting the earlier release through normal dispatch), and an `ACTION_UP` with `KEYCODE_UNKNOWN` is swallowed even when no shortcut down is active.
- Hardware `KEYCODE_ESCAPE` in `SearchBarView.key` closes search on `ACTION_DOWN` and returns focus to `VtView` without recording `shortcutKey`, so the matching `ACTION_UP` dispatches to `VtView` and can emit an unmatched Escape release when keyboard release reporting is enabled.
- Focused search's extra-key Left/Right editing uses single UTF-16 units. It can place the caret between the halves of a supplementary character, where inserting a symbol breaks the pair. The backward-delete rule also removes a single unit for an empty selection.
- The OSC 52 clipboard-read preview slices the first 300 UTF-16 units before appending an ellipsis, which can split a supplementary character in the preview (Allow still sends the full captured text).

## Open questions

- Should a single-target “Close other tabs” confirmation identify the affected tab?
- Should a clipboard answer ever be remembered for a session?
- Should entering a search query select its newest match before the first step?

## To verify

- On physical devices, check interrupted IME animations and side cutouts, Back with the search field active, and tab-list TalkBack traversal before and after dismissal.
- Check hardware chord and volume-key delivery, held presses and haptics with vendor keyboards and system feedback settings.
- Check vendor notification/Doze settings and fallback handlers, external link viewers and end-to-end browser callback navigation.
- Check clipboard focus timing and import-result delivery across multi-window focus changes, privacy unlock and activity recreation.
- Check language switching and current-tab restoration, translated labels at larger display sizes, and control/selection-menu contrast under device wallpaper palettes.
