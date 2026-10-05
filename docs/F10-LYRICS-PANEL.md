# Experimental Android lyrics panel

Branch: `feature/android-lyrics-panel`, based on committed `main` at `10456ed`.

## Using it

Enable **Show lyrics panel** in Settings → Display, or in the CarPlay **F10 Play
setting** controls (also reachable with the three-finger downward swipe or Menu
key). The preference is per Android device and defaults to off. When Spotify is
playing and the panel is hidden, a floating **Lyrics** button appears on the
passenger side. It requires Spotify to be identified in the iPhone's retained
source-app metadata, and a song title plus active playback. It hides for other
apps, paused playback, disconnected sessions, and narrow/portrait windows. Opening
it uses the same saved setting and resize reconnect as the existing controls.

On a wide landscape display, CarPlay stays on the driver's side:

- Right-hand drive: lyrics left, CarPlay right.
- Left-hand drive: CarPlay left, lyrics right.

Hide and show automatically reconnect CarPlay to negotiate the new projection
width. This lets the iPhone reflow its interface to fill the pane, without the
large black margins introduced by scaling a canvas with a different aspect
ratio. Audio can briefly pause during the reconnect. The lyrics setting is
retained, and the app's existing reconnection/recovery behavior applies.

The panel appears only at widths of at least 700 dp, heights of at least 260 dp,
and landscape aspect ratios. Narrow/portrait windows restore the full projection
area and retain the lyrics preference for the next wide landscape view. Lyrics
and CarPlay have separate touch surfaces; touches in letterbox bars are ignored.
The lyrics panel respects its outside display cutout and system insets.

## Lyrics behavior

- Uses retained iAP2 song metadata and the same monotonic playback clock as the
  Android media session. No Spotify app, session cookie, token, or notification
  permission is required.
- Tries LRCLIB, including a retry without album if an album-specific lookup
  returns no result. Uses LrcAPI's public `/jsonapi` endpoint as a fallback.
- The first returned lyrics result is displayed automatically, as requested.
  **Choose lyrics** shows available recordings and marks the current selection.
  When only one result is available, it fetches additional LrcAPI title-based
  matches on demand. **Search again** permits editing the title and artist,
  including local artist names. Covers, live recordings, aliases, and durations
  can differ; choosing a recording is the way to correct an unsuitable first
  result.
- Automatic retrieval occurs on song metadata changes, rather than on playback
  ticks. Successful lyrics and manual choices for eight songs are retained in
  memory. Highlighting runs locally every 250 ms and only changes text at line
  transitions. Pause and backward seek follow playback state/position.
- Handles multiple LRC timestamps, fractional timestamps, offsets, untimed
  lyrics, and instrumental responses. Untimed lyrics are scrollable.
- Hidden/stopped panels suspend polling and cancel outstanding work. Generation
  checks discard responses from earlier tracks or stopped views.
- HTTP has six-second connect/read timeouts, a two-megabyte response cap, a
  five-thousand-line parser cap, and per-host HTTP 429 cooldowns respecting
  `Retry-After`. Network/provider failures are distinct from missing lyrics.

Song title, artist, album, and duration are sent to these services. The Android
device needs internet through its own mobile data or internet Wi-Fi; wireless
CarPlay does not relay the iPhone's mobile data to Android. No network mode,
Wi-Fi connection, or phone data setting is changed by the lyrics client.

Sources: [LRCLIB](https://lrclib.net/docs),
[LrcAPI legacy lyrics](https://docs.lrc.cx/docs/legacy/lyrics/).
The deployed LrcAPI uses `lrc` where its documentation shows `lyrics`; both are
accepted. The documented newer API endpoints returned 404 during investigation.

## Design and validation

Native views use neutral, appearance-aware CarPlay-style surfaces, grouped into
three rounded cards: song title/artist, scrollable lyrics, and Find/Choose, Retry,
and Hide controls. Current timed
lyrics are 24 sp, bold, with a mint accent; surrounding lines and untimed lyrics are
18 sp. Provider names appear only in the recording chooser, not the main panel.
Actions have at least 48 dp hit targets. Text wraps and the lyrics area
scrolls independently. The optional Spotify shortcut sits opposite the CarPlay
dock and respects system bars and display cutouts. It uses the host's existing
two-second foreground configuration tick; it performs no lyrics requests while
the panel is hidden.

Measured contrast against the card surface: dark primary text 14.2:1, secondary
text 8.1:1, active lyrics 10.6:1; light primary text 17.6:1, secondary text 5.6:1,
active lyrics 6.6:1. The active line also uses bold weight.

Automated checks cover LRC parsing, seeking, provider response formats and
failures, duration matching, instrumental/untimed results, on-demand recording
alternatives, large-text panel controls, passenger-side placement, portrait
fallback, canvas renegotiation during hide/show, and playback clock pause/clamping
on Android API 28 and 36. Existing common regression tests, Android lint, and
standalone debug builds were also run.

Physical A5360 landscape testing confirmed LRCLIB timed lyrics for Spotify's
「讓我永遠愛你」, active line changes while playing, and identical lyrics/playback
position across two paused captures. The final build also displayed LrcAPI timed
lyrics for 「矛盾一生」 by JW, automatically selected the first result, opened the
recording chooser, showed bold mint lyrics without a provider label, and placed
the lyrics on the left in right-hand-drive mode. Session logs confirmed canvas
renegotiation between 1560×1080 (lyrics shown) and 2400×1080 (lyrics hidden), with
the corresponding four-/five-column CarPlay app layouts filling their panes.
The earlier targeted regression suite passed 49 checks; lint and the APK build
passed. Manual recording choices are checked for persistence across playback
updates on API 28 and 36.

Limits: provider coverage/availability and first-result accuracy are variable.
There is no disk cache, bundled lyrics database, local-LRC import, or new iOS
app in this experiment. The P205's lyrics playback remains covered by the API 28
automated checks rather than a physical Spotify lyrics test.

## Build 39 update (2026-10-06)

- The 55 affected checks passed, covering API 28/36, 1.5× text scaling, phone
  removal/restoration, Spotify eligibility, both passenger-side positions,
  projection resizing, and connection-settings refresh. APK assembly and lint
  passed; lint has 18 existing warnings and no errors.
- On the physical A5360 in landscape/right-hand-drive mode, the new cards displayed
  timed Spotify lyrics with bold mint active lines. Hiding the panel restored the
  full-width CarPlay canvas and revealed the floating Lyrics action on the left,
  clear of the right-side dock.
- Build 39 was installed and its package version verified on both Samsung
  receivers. The A5360's temporary landscape rotation override was restored to
  its original free-rotation setting. Left-hand-drive shortcut/panel placement
  and phone removal/restoration were checked through the API 28/36 automated
  tests; real Bluetooth pairings were not removed during validation.

## Removing phones

**Your phones → Remove** removes a receiver's saved selection/list entry from
F10 Play and prevents it being selected automatically. Removing the selected
phone stops its current CarPlay session. Android Bluetooth pairing is kept.
**Add phone** lists paired phones, including removed entries; selecting one
restores it. Removal and restoration persist across app restarts.
