# Experimental Android lyrics panel

Branch: `feature/android-lyrics-panel`, based on committed `main` at `10456ed`.

## Using it

Enable **Show lyrics panel** in Settings → Display, or in the CarPlay **F10 Play
setting** controls (also reachable with the three-finger downward swipe or Menu
key). The preference is per Android device and defaults to off.

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

Native views reuse F10 Play's Material-style semantic colors. Current timed
lyrics are 24 sp, bold, with a mint accent; surrounding lines and untimed lyrics are
18 sp. Provider names appear only in the recording chooser, not the main panel.
Actions have at least 48 dp hit targets. Text wraps and the lyrics area
scrolls independently. No animations or floating button over CarPlay.

Measured contrast against the panel surface: dark secondary text 8.57:1, dark
active lyrics 10.90:1; light secondary text 6.20:1, light active lyrics 6.64:1.

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
The final targeted regression suite passed 49 checks; lint and the APK build
passed. Manual recording choices are checked for persistence across playback
updates on API 28 and 36.

Limits: provider coverage/availability and first-result accuracy are variable.
There is no disk cache, bundled lyrics database, local-LRC import, or new iOS
app in this experiment. The P205 was not connected for a physical test; Android
9 remains covered by the API 28 automated checks.
