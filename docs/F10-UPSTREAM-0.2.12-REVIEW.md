# F10 Play: selected fixes through DiPlay 0.2.12

Reviewed upstream `v0.2.10..v0.2.12` on 2026-10-05. This is a selective fix port,
not a wholesale 0.2.12 merge. F10 branding, device profiles, Android 9 support,
controls, and the optional Android lyrics panel remain in place.

Upstream release: <https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.12>

## Integrated fixes

| Fix | Upstream source | F10 adaptation |
| --- | --- | --- |
| Avoid resending album artwork for elapsed-time/play-state-only updates | `54e5611` / #162 | Preserve F10's monotonic lyrics clock and pause/seek handling. |
| Keep artist across incremental title updates | `f6b54ea` / #161 | Retain explicit empty-title clearing. |
| Match Apple devices in the USB attachment filter | `21dc8c8` / #224 | Keep the CH341 match; no accessibility auto-confirm service added. |
| Scope the wired VPN to the app | `ffc9306`, also #168 | Fail attachment if allowlisting fails; never retry with a device-wide tunnel. |
| Release codecs after configure/start failure; bounded failure diagnostics | `28ae094` | Retain F10 output routing and API 28 attributes fallback. Apply the same cleanup helper to the microphone Opus encoder. |
| Keep pending album art and clear confirmed failed/cancelled artwork transfers | `5156be7` / #228 | Keep F10's background artwork decoding and lyrics state. |
| Use the latest settled display size after reconnect | `0c2848c` | Keep passenger-side lyrics resize reconnects and startup prerequisites. |
| Refresh connection settings when the projection activity resumes | `eccdc0d`, corrected in `d9d87fd` / #196 | Refresh only when the settings menu is closed so unsaved edits survive. |

The API 28 audio-attributes guard was already present in this fork. Its upstream
helper/fallback-attribute correction and regression tests are included with the
codec fix.

## Separate F10 microphone repair

- Start microphone input after the stream SETUP response is flushed, even when
  the iPhone has not sent any speaker audio.
- Accept the input-capable default/compatibility streams advertised by `/info`,
  as well as telephony and speech recognition. Require a valid input destination
  and a supported PCM/Opus format. Output-only media/alert streams never record.
- Prefer the receiver's built-in microphone for non-call input using
  `AudioRecord.setPreferredDevice` (available on both tested Android versions).
- Mark recording privacy-sensitive on Android 11+; preserve Android 9 capture.
- Release microphone capture and audio mode on disconnect, not only TEARDOWN.
- Calls retain the configured communication route, including headset microphones.
- Never log microphone packet payload bytes.
- Honor the independent 16/24/48 kHz Opus microphone rate and frame duration;
  speaker decoding remains 48 kHz. Use the bundled libopus for rates/frame sizes
  the Android encoder cannot accept, with matching RTP sample increments.
- Read packet-sized PCM buffers to avoid batching several voice packets together.
- Use the standard MIC source for non-call input. Diagnostics retain only aggregate
  signal peaks and sample counts; no microphone recordings or payloads are saved.

Android may reject a preferred device or override it through firmware policy.
The existing microphone diagnostics report the actual routed device type and
capture/send counters; a successful preference request alone is not proof of
end-to-end recognition.

## Not imported in this fix pass

0.2.12 also adds Same LAN, wheel joystick/zoom controls, new BYD cluster ownership
and restoration paths, scan-pausing through local ADB, hotspot command fallbacks,
night-mode sensors, live picture controls, and expanded resolution settings.
These introduce new features and vehicle-specific behavior rather than small
corrections to the two Samsung receiver profiles. Their dependent recovery fixes
need a separate integration and vehicle validation pass.

The upstream release itself still requests current-device evidence for
calls/Siri; these microphone changes are a separate F10 fix, not an upstream
claim that those issues were resolved in 0.2.12.

## Validation

- Shared suite: 420 cases, 419 passed and one existing skipped case; no failures.
  Includes microphone routing on API 28 and API 36.
- Native Opus encode/decode check passed at 16/24/48 kHz with 20/40 ms frames.
- Affected host, controls, media metadata, lyrics, USB-filter and settings-resume
  suite: 55 passed, including host settings tests on API 28 and API 36.
- Debug APK assembly and Android lint passed; lint reports 18 warnings and no errors.
- Public-tree credential check and whitespace/conflict checks passed.
- APK version code 38 installed on both receivers. No claim of a complete upstream
  0.2.12 upgrade. Temporary system rotation overrides have been restored.
- On 2026-10-06 the user confirmed Siri recognition works on the Android 9 tablet
  (R52N81SZNRF); capture peaks and UDP counters also showed usable input.
- A5360 recognition remains unresolved: recording permission is granted, Android
  reports the built-in route and no silencing, but capture is mostly near zero.
  A separate temporary signal-only probe also reproduced weak capture outside
  CarPlay. Further device-route diagnosis is required; this is not a verified
  A5360 microphone fix. Google Maps voice search and calls still need live checks.
- Rebooting the A5360 did not restore capture. The user also reported that a normal
  Samsung Camera recording did not capture voice properly. Both bottom/default
  and explicitly preferred rear-microphone probe routes remained weak, including
  unprocessed capture. Global microphone access is enabled and Android's mute
  flags are false. Samsung microphone diagnostics are needed to distinguish a
  device hardware/firmware fault from other device-wide interference.
