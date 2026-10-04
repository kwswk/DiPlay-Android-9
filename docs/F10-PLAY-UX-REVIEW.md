# F10 Play: UI review and next additions

## Summary

**Overall: Good, with connection recovery the next priority.** This is a native Android CarPlay receiver used on a Samsung tablet and A5360 in an F10. Its main job is to get the selected iPhone into CarPlay with little attention or repeated setup. The large connection action and compact landscape layout already serve that job well.

This review uses the current source and the physical portrait/landscape screenshots captured during the 0.2.10 integration. Apple's accessibility, layout, writing and icon principles apply; Android navigation, permissions and system controls remain native. A screen reader audit, maximum-font physical check and an on-road usability study have not been completed, so this is not an accessibility certification.

## Changes delivered

- A Material-style home now groups the CarPlay title, live connection status and primary action in one tonal card. Secondary actions use quieter filled tiles, and settings use consistent icon containers and 16 dp corner shapes. Header navigation uses text actions instead of oversized outlined buttons.
- App, launcher, widgets, notification title and localized UI now use **F10 Play**. Upstream credits and the DiPlay launcher integration name remain attributed correctly.
- A navy icon with a white F and pale-blue play mark replaces the green CarPlay artwork as the app's identity. Android applies its launcher mask; Android 13+ has a monochrome layer for themed icons. The same mark appears in the app header, startup screen and widget.
- The package ID and stored preferences are retained, so installation updates the existing app. New diagnostic exports use `Downloads/F10 Play`.

The icon stays close to the product's existing play cue and blue UI. Its two bold shapes stay legible at launcher size. `app-icons.md › Design`: “Include text only when it’s essential to your experience or brand.” `app-icons.md › Icon shape`: “Keep primary content centered to avoid truncation when the system adjusts corners or applies masking.” References are in `.agents/skills/apple-design/references/hig/` in the workspace.

## Critical

No new critical issue is established by this review. The prior screen clipping and dock overlap were fixed in the 0.2.10 integration. Full TalkBack and maximum-text-size checks should be completed before calling the interface fully accessible.

## Improvements, in priority order

| Priority | What I would change | Why it helps | Small first version |
| --- | --- | --- | --- |
| High | **Show connection progress and the next useful action on Home.** The home screen distinguishes connected/connecting/stopped, but detailed startup status lives on the projection screen. | A failed connection should tell you whether to check Bluetooth, Wi-Fi or an iPhone prompt. | Surface the existing connection stages, retain the last failure, and show one contextual action such as Retry, Open Bluetooth settings or Check hotspot. Keep technical logs under Help. |
| High | **Make the actual audio route visible.** The Home tile shows the selected preference, such as Auto; that alone does not tell you where sound is going. | “Connected but silent” is different from a connection failure. | Show the active output device when Android reports it, with an audio test in the route picker. Keep music and call output labels distinct when they differ. |
| Medium | **Explain when display changes take effect.** Resolution/frame-rate/codec choices can require a new session. | Saving a value should not leave people guessing whether it has applied. | Show a persistent “Saved for next connection” message with an explicit Apply and reconnect action when a session is active. Avoid silently restarting a working connection for each toggle. |
| Medium | **Finish the floating control behavior.** It now drags and remembers position, but it can still cover content. | The app should stay out of the map's way without making its controls hard to recover. | Snap to the nearest edge after dragging, add Reset position, and offer subtle fading when idle. Keep its 48 dp touch area and a visible focus state; do not make the only entry point disappear. |
| Medium | **Make the projection menu quicker to use.** It currently opens a text list for audio, settings and disconnect. | The frequent actions need larger, easier-to-scan targets than the full settings list. | A native panel with Audio output, Reconnect and Return to car home. Put less-used settings behind one clearly labeled action. |

These priorities are design judgments based on this app's source and use case. Supporting principles:

- `loading.md › Showing progress`: “Clearly communicate that content is loading and how long it might take to complete.” Use named stages and an indeterminate indicator; do not invent percentage completion or an ETA.
- `settings.md › Task-specific options`: “When possible, prefer letting people modify task-specific options without going to your settings area.” Audio and reconnect belong with the current session.
- `layout.md › Visual hierarchy`: “Use progressive disclosure to make layouts cleaner and easier to interact with.” Keep troubleshooting detail behind the specific problem.
- `accessibility.md › Vision`: “Support larger text sizes.” Test the panel and connection messages at 200% text on both compact portrait and short landscape layouts.

## Useful additions

1. **A connection check.** A small checklist for selected iPhone, Bluetooth, required permissions and the configured Wi-Fi mode. Each failed item opens the relevant existing setting. This improves the current setup flow without adding a mandatory onboarding carousel.
2. **Recent connection attempts.** Show the last few attempts with time, duration and the failed stage. A Copy summary action can use the existing redacted diagnostic export. No account or automatic upload is needed.
3. **Display presets with a preview.** Extend the existing balanced preset into Smooth, Cooler and Larger controls. Explain resolution, frame rate and tradeoffs in ordinary language, and preview the CarPlay content size before reconnecting. This is more useful than adding more sliders.
4. **A now-playing home widget.** Reuse the metadata/artwork already integrated from 0.2.10 to show title, artist and playback controls outside projection. The existing navigation and launch widgets remain separate, so users can choose what they need.
5. **An optional heat notice for long sessions.** If Android reports a sustained thermal problem, offer a lower frame rate with a clear explanation. Start with a notice and an explicit action; do not change streaming settings unexpectedly.

I would ship connection feedback and actual audio output first, then refine the CarPlay receiver controls. The other additions should follow observed daily-use problems. Cloud accounts, social features and another full dashboard would add complexity before they improve the connection experience.

## What works

- The right-side primary action suits the current right-hand-drive setup.
- Home and the six settings categories fit the A5360 landscape viewport at its current 1.15 text scale.
- Light/dark resources, consistent vectors and wrapping controls provide a coherent native base.
- Existing Bluetooth setup, automatic connection, audio selection, navigation widgets and diagnostic export give us useful functions to improve rather than rebuild.

## Visual direction

The Material-style palette uses light background `#F3F5F9`, surface `#FFFFFF`, text `#17243A`, secondary `#52627A`, accent `#245FD6` and tonal fill `#E5EDFC`. Dark mode uses background `#10141C`, surface `#1B2230`, text `#EEF2FA`, secondary `#B3BFD2`, accent `#AAC7FF` and tonal fill `#23344F`.

Measured text/surface contrast is 15.56:1 light and 14.20:1 dark; secondary text is 6.20:1 and 8.57:1. Primary button text is 5.70:1 light and 8.22:1 dark. The new icon uses `#102344`, `#F0F4FA` and `#A6C8FF`.

Retain Roboto with 16 sp body text, 18 sp actions and a 24–34 sp heading hierarchy. Keep one obvious connection action, use the rest of the screen to explain state, and avoid extra decorative cards or animations.

```text
Phone                          Landscape
F10 Play                       F10 Play                 Car home
[ Selected iPhone ]            [ iPhone + state ] [ Open CarPlay ]
[ Stage / recovery ]           [ Next useful action ] [ Audio ][ More ]
[ Connect / Open CarPlay ]
[ Audio ][ More ]
```

The product's character comes from its driver-oriented connection action and quiet F/play identity. The next priority is better connection and audio information at the right moment.


## Delivery checks

- Version code 30 keeps the existing application ID and Android 9 minimum version.
- Common unit tests, mobile lint and the standalone debug build passed; layout tests cover API 28 and API 36 in portrait and landscape.
- Physical A5360 dark-mode landscape and SM-P205 light-mode landscape were inspected after installation. Home and settings fit both devices in landscape; the A5360 portrait home was also checked at its existing 1.15 text scale.
- The icon has separate Android adaptive foreground/background layers and an Android 13+ monochrome layer. No new runtime dependency was added.

## CarPlay receiver shortcut

The former BYD receiver shortcut now defaults to “F10 Play setting” and advertises the F10 icon. Existing BYD and F10 Play labels migrate on load; custom labels and uploaded icons remain supported. The floating ⋮ button has been removed from projection. Tapping the shortcut opens the controls dialog, without launching Android Home or disconnecting the session. The separate parked-video request retains its existing handler. Reconnect the iPhone after installing to refresh the advertised shortcut.

## Recovery and controls update

The CarPlay shortcut and three-finger swipe now open a native controls panel with Resume CarPlay, Audio output, Reconnect, Settings, and a separated Disconnect action. Buttons have a minimum 56 dp height. Landscape uses two columns at 600 dp and above; narrow screens stack the actions, with scrolling available for larger text. Existing light/dark semantic colors are retained. No projection overlay button is added.

Connection setup now shows explicit USB, Wi-Fi, permission, pairing, and opening stages. Automatic recovery shows its retry number and countdown. Reconnect uses the existing stack teardown/restart path and cancels a queued automatic retry; repeated taps during teardown are ignored. A successful session cancels pending retries.

Validation: the full common-module suite passes, including API 28/36 control layouts, 1.5× portrait text, short landscape connection layout, and manual retry cancellation. Android lint and the debug APK build pass.

### Performance baseline

Existing VideoStats records received/rendered fps, maximum arrival gap, bitrate, decoder recoveries, and touch-to-frame samples. Audio stats already record packet drops and AudioTrack underruns. Reuse these five-second counters for a 10-minute parked navigation/music run on each device before changing resolution, frame rate, or buffers. A static map may legitimately produce fewer frames; compare received and rendered throughput with animated content. These counters are not an end-to-end dropped-frame measurement.

At implementation time the A5360 was available without an active CarPlay stream and the P205 was disconnected. No live streaming baseline or performance improvement is claimed. Current display preferences remain unchanged unless a profile is selected. Android 9 recommends 70% resolution / 30 fps; modern devices recommend native / 60 fps. These are starting profiles, not measured performance guarantees.

### Complete follow-up

- **Audio:** Audio output and Connection health show the active AudioTrack route separately from the saved preference; idle playback is explicitly unknown. Missing selected outputs show an actionable explanation.
- **Display profiles:** Recommended is device-specific; Light and Smooth remain available on either device. Preferences stay local. After the first rendered video frame, the captured display settings become the restore point; changing preferences alone cannot overwrite it. Restore covers resolution, fps, codec/software-decoder choice, UI scale, and physical display width. It confirms video rendering, not prolonged stability.
- **Connection health:** available from the controls panel and Settings → Help/support → Diagnostics. Shows attempt/reconnect counts, first-picture timing, latest video sample, cumulative audio packet drops/underruns for the attempt, sample age, battery temperature, and Android thermal-throttling warnings where supported. Counters live in memory for this app process; exported reports include them. No live samples is distinct from zero errors.
- **Controls access:** CarPlay shortcut, three-finger swipe, or a keyboard/head-unit Menu key. The projection overlay button remains removed.

The shared test suite has 390 passes and one existing host-network skip; the final 37 targeted common tests pass after the full common suite passed. No physical streaming benchmark is claimed without an iPhone session.

Physical UI verification on SM-A5360: all six controls are visible in landscape, portrait is usable, and Connection health correctly identifies absent live media. Temporary rotation overrides were restored after the check. The P205 was disconnected; API 28 coverage is automated rather than a physical install of this update.
