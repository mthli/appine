# android/settings Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/settings.md` for the paired decision history (not yet created; decisions from before the split are D10–D12, D21–D23, D25, D26, D28, D31–D33, D35, D36, D38, D41, and the Settings halves of D1, D3, D9, D14 and D15 in `.agents/decisions/android/setup.md`).
>
> Verified: 2026-11-21 (split out of the android/setup full mapping of 2026-11-15; the boundary was checked against the source at the split)
>
> Maintained: 2026-11-28 (targeted verification only since the full mapping; latest change: package source summary, preset picker and custom configuration confirmation)

## Responsibilities

`android/settings` is the app's settings screen:

- displaying service-owned preferences and forwarding changes to `TerminalService` (distribution
  switch and reset, login account, screen-off running policy, external file access, CJK wide-cell
  fill, cursor shape and blinking, tab-list button visibility, volume-key tab switching, execution
  mode and Vulkan GPU);
- choosing a package source preset for the running guest and confirming replacement of custom sources;
- exposing service-independent Android and debugging controls (conditional notification
  authorization and battery-usage rows, authenticated privacy mode, authorized diagnostic log
  export via Save to file or Share);
- About actions (Telegram, project website, privacy policy, open-source licenses and version /
  Google Play link).

Root: `android/app/src/main/java/io/github/mthli/appine/SettingsActivity.kt`.

Outside the module: rootfs import, downloads, mirrors, login account resolution and shell
integration (`android/setup`); service lifecycle and the `settings` preferences store
(`android/service`); diagnostic archive retention and `DiagnosticProvider` (`android/reports`);
the shared page layout, `Ui.showPrivate`/`monospaceMessage`, `NotificationPermission` and
`BatteryOptimization` (`android/terminal`); open-source notices UI (`android/licenses`);
app-wide authentication and window protection (`android/privacy`).

## Key types

- `SettingsActivity` — an `AppCompatActivity` (`Privacy.Listener`, `Privacy.ExportListener`)
  hosting `SettingsFragment` (in the same file) on the Material 3 frame it shares with
  `android/licenses` (`android/terminal`'s `page.xml`).
- `SettingsFragment` — a `PreferenceFragmentCompat` and `TerminalService.Listener`.
  `PreferenceCategory` groups standard `Preference` actions, `SwitchPreferenceCompat` toggles
  (a `MaterialSwitch` widget) and `ListPreference` choices (login account, screen-off running and
  cursor shape). Groups: System (distro, package source, login account, reset), Android (conditional notification
  and battery usage actions, running policy, privacy mode, file access), Terminal (CJK characters
  filling two cells, the cursor's shape, whether it blinks, tab-list button visibility, the volume
  keys switching tabs), Debugging (execution mode, Vulkan GPU toggle and diagnostic export, in all
  builds) and About (Telegram, project website, privacy policy, licenses, version).
- `ChoiceDialog` — nested `ListPreferenceDialogFragmentCompat` using `MaterialAlertDialogBuilder`
  for login, running policy and cursor choices.
- `PendingLogSave` — fragment-scoped `ViewModel` staging a single-use authorized diagnostic ZIP
  across configuration recreation while the SAF `CreateDocument` picker is open.

## Public entry points

- `SettingsActivity`, opened from the terminal's ⋮ menu (not exported).

## Data flow / lifecycle

### Android and diagnostic actions

- The Android group starts with an Enable notifications action only while app notifications are
  disabled, regardless of whether the terminal banner was dismissed. It uses `android/terminal`'s
  `NotificationPermission` helper and shared request history: request once on Android 13+, then
  open system notification settings. The action checks privacy and works without service binding.
  The fragment refreshes visibility when creating preferences, after permission results and on
  resume, including return from system settings. Granting removes it; revoking restores it.
- App battery usage follows notifications and precedes the screen-off policy. It opens the
  system app details for this package. The row reads the system Doze exemption on creation and
  resume, remains available after banner dismissal, and disappears when the package is exempt.
- Diagnostic export is independent of service binding and kernel readiness. Its protected dialog
  offers Save to file or Share. Both call `Privacy.requestLogExport` before generating a ZIP;
  privacy mode requires fresh authentication even when Settings is already unlocked.
  Only the authorized callback starts `appine-export`. A stopped/replaced fragment or expired
  authorization deletes its prepared result instead of handing it off.
- Save stages its already-prepared ZIP in `PendingLogSave` and then launches
  `CreateDocument("application/zip")`. The mode and pending file reference survive configuration
  recreation, never process death; the authority only covers this archive. A picker result consumes
  the reference once. Cancellation or permanent owner destruction while pending deletes the file;
  a valid destination transfers ownership to `appine-save-logs`, which opens the source before
  `openOutputStream("wt")` and deletes the ZIP after success or failure. Save files use
  `cache/diagnostic-saves`, isolated from share-cache pruning (`android/reports`). The row stays
  disabled while preparing, pending or saving.
- A consumed picker result calls `Privacy.deferNextUnlock` while the page is paused. Its next
  gate resume can show the locked shield without a redundant automatic authentication prompt;
  this never unlocks Settings or permits new exports. The already-authorized transfer may finish
  while locked/backgrounded. Ordinary unlock cannot replay a cancelled or consumed save.
  Share instead grants temporary read-only access through `Diagnostics` (`android/reports`) after
  its final check. Failures restore the row and show a localized message. A provider failure may
  leave an empty or incomplete destination; cancelling authentication occurs before the picker
  creates any document.
- Privacy mode is an authenticated exception to service-owned changes: its nonpersistent switch
  asks before enabling, then calls the shared `Privacy` gate; disabling also authenticates. No
  setting changes on cancellation or failed/unavailable authentication. File access stays disabled
  while privacy is on, with a localized explanation; turning privacy off leaves file access off.
  Material dialogs use `showPrivate`, and restored choice dialogs use `Privacy.protect`.

### Service binding and service-owned rows

- Binding: the fragment's `onStart` starts and binds `TerminalService`, `onStop` unbinds; once
  bound it listens, renders, shows a pending notice and loads the regular accounts on a background
  thread (`Rootfs.regularAccounts`), again when the kernel is ready; account loads use the
  application context and a generation to discard results after stopping or a newer load.
  `onSettingsChanged` renders; `onProgress`, `onRestarting` and `onExitAll` finish the screen, so
  a switch, reset or restart goes back to the terminal, which shows the progress.
- The package source action reads the live guest file when bound, when the machine becomes ready
  and again on tap. It is enabled on a running machine outside a distro switch. The picker checks the
  recognized preset and asks before replacing a custom configuration, naming the destination file.
  A serial worker applies the selection to the captured machine and rereads the file. Stale selections
  prompt a retry. Callbacks check the current request, service and machine before updating the page.
- Service-owned rows read the service's state and call it:
  - distro: usable during a switch (it then cancels the download, after a confirmation) or when a
    switch is possible, a start failure included; choosing another distro or Reset asks first;
  - login account: whenever a store is installed and no switch runs, with a toast naming the
    account new tabs log in as;
  - reset: whenever a switch is possible;
  - execution mode (tier, in all builds): while the kernel runs with no switch, a different tier
    asking to restart; the summary names the current mode and Tier number, with a slower hint
    for Tier 0. Separate choice labels mark Tier 1 as the default and Tier 0 for troubleshooting;
  - Vulkan GPU (default off, in all builds): while the kernel runs with no switch, toggling either
    way shows a protected restart confirmation (`showPrivate`) before calling `service.setGpu`;
    the summary notes experimental device-dependent compatibility, switching to "Unavailable on this
    device" when enabled on a running kernel whose host probe registered no renderer;
  - screen-off running (Allow sleep, Run while busy or Always run, with the selected policy's
    description; BUSY explains its 30 s idle grace and network/scheduled-work limitation), file
    access (outside privacy mode), CJK fill, cursor shape (Block, Bar or Underline, the summary
    naming the current one), cursor blink, the volume keys switching tabs and showing the tab-list
    button (all three on by default): usable at any time; the service gives every tab the new
    cursor values at once (`android/service`), and the terminal reads the CJK, volume-key and
    tab-list visibility values itself (`android/terminal`).
- Service-dependent preferences are disabled before binding, on disconnect and on stopping;
  notification authorization, battery usage, diagnostic export, Telegram, project website, privacy
  policy, licenses and version remain usable. All preferences have stable resource-name keys and
  persistence disabled: they render service values and their change listeners call service setters,
  never writing a second preference store (package sources use the guest file; privacy, notification authorization and battery usage
  use shared controllers). AndroidX manages row binding, disabled states and accessibility;
  `SwitchPreferenceCompat` binds the Material widget through `switchWidget`.
- Login, running policy and cursor choices use `ChoiceDialog`: AndroidX keeps the entries,
  selection and dialog state, and Cancel, Back or an outside tap commits nothing. Distribution,
  reset, tier and notices keep their explicit Material dialogs and confirmation flows. File access
  also asks on every enable, warning about reading or copying sensitive home/system files; only
  Enable saves true, while Cancel, Back, outside tap or recreation leaves it off. Disabling acts
  immediately through the service.

### About and presentation

- About starts with Telegram (`https://t.me/appinesh`), followed by the project website
  (`https://github.com/mthli/appine`) and privacy policy (`https://appine.sh/privacy`). All three
  use the shared `link` preference helper, show their URL as the summary and open it with AndroidX
  `CustomTabsIntent`, without pinning a browser package. Telegram uses a non-translatable brand
  label in every locale. A browser without Custom Tabs can handle the same VIEW intent; no handler
  produces the shared `link_no_app` toast. The licenses summary names GNU AGPL 3.0 or later; the
  full copyright, warranty disclaimer and license text remain in the Appine notice on the license
  page.
- About's final Version action shows `BuildConfig.VERSION_NAME` followed by
  `BuildConfig.VERSION_CODE` in parentheses (`1.0.0 (1)`). It opens the HTTPS Google Play details
  URL for `io.github.mthli.appine` (regardless of the debug `.debug` application ID suffix), first
  restricted to `com.android.vending`, then without a package if no Play Store activity handles it;
  no URL handler shows `link_no_app`. The link can be built before publication, but the store
  listing is unavailable until published.
- Look: Material 3 light or dark with the system, with the wallpaper's colours from Android 12 on
  (`DynamicColors`), edge to edge. A dark-mode or display size change recreates the screen (it
  handles neither `uiMode` nor `density`), which restores the fragment, scroll position and an
  open login/running-policy/cursor choice dialog, then rebinds and renders. Other open dialogs are
  dropped. The activity applies top and side insets; the fragment pads its RecyclerView above
  navigation bars with clipping disabled, so the final preference can scroll clear of them.
  Adapter state restoration waits for the first service render and window insets, so initially
  empty summaries and missing bottom padding cannot shift the saved scroll position.

## Dependencies (inbound / outbound)

### Inbound

- `MainActivity` (`android/terminal`, opens `SettingsActivity` from the ⋮ menu).
- Instrumentation tests (`android/tests`, e.g. `GpuTest`, privacy and diagnostic export checks).

### Outbound

- `TerminalService` and its `Listener` (`android/service`) for binding, state rendering, setters
  and notices.
- `Rootfs` (`android/setup`: `Distro`, `installed`, `regularAccounts`, `loginAccount`, `packageSources`, `applyMirror`).
- `Diagnostics` (`android/reports`: archive export, save/share paths and `DiagnosticProvider`
  grants).
- `android/privacy` (`Privacy` mode changes, `requestLogExport`, `deferNextUnlock`, `protect`).
- `android/terminal` (`page.xml`, `preference_switch.xml`, `Ui.showPrivate`,
  `NotificationPermission`, `BatteryOptimization`).
- `LicensesActivity` (`android/licenses`).
- Material Components, AndroidX Preference, AndroidX Browser (`CustomTabsIntent`) and
  `BuildConfig`.

## Invariants and gotchas

- Settings must set the app bar's `liftOnScrollTargetViewId` to AndroidX Preference's
  `recycler_view`: the list is nested inside the fragment container. Without that target,
  Material's layout and nested-scroll callbacks inspect different views, so preference updates
  can reset the app bar's lifted background even while the list remains scrolled.
- Every preference must keep persistence disabled and route writes through `TerminalService` (or
  `Rootfs` for package sources and the shared `Privacy` / notification / battery controllers) so Settings never writes a second
  preference store or bypasses service side effects.
- Material dialogs must use `showPrivate` and restored choice dialogs `Privacy.protect` so
  separate dialog windows follow `FLAG_SECURE` under privacy mode.

## Open questions

None.

## To verify

- Notification authorization on Android 11–12, vendor settings and TalkBack (API 34 checks passed
  for banner dismissal, first request, denial then system settings and removal after grant).
- Diagnostic export with real biometric hardware, cloud document providers and receiving the ZIP
  in third-party apps (API 34 covered privacy off/device credentials, pre-picker auth, Downloads
  save, cancelled-picker cleanup and orphan cleanup).
- File-access consent on physical devices (API 34 covered English/Chinese layout, cancellation,
  recreation and enabling/disabling).
- About's version row and Play Store navigation, and Telegram, project website and privacy-policy
  links (including Custom Tabs, non-Custom-Tabs browser, no-handler toast and return to Settings)
  on a device.
- Vulkan GPU toggle layout and TalkBack across all five locales outside English/Chinese checks on
  Galaxy S23.
