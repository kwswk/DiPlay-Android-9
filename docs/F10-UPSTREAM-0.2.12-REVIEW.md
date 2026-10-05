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

- Shared suite: 418 cases, 417 passed and one existing skipped case; no failures.
  Includes microphone routing on API 28 and API 36.
- Affected host, controls, media metadata, lyrics, USB-filter and settings-resume
  suite: 55 passed, including host settings tests on API 28 and API 36.
- Debug APK assembly and Android lint passed; lint reports 18 warnings and no errors.
- Public-tree credential check and whitespace/conflict checks passed.
- APK version code 35. No claim of a complete upstream 0.2.12 upgrade.
- Installed version code 35 successfully on the A5360 and verified that microphone
  permission is granted. Restored the temporary system rotation overrides used
  during landscape testing. The device then disconnected from ADB before live
  diagnostics could be collected. Siri and Google Maps recognition remains to be
  verified on the final build.
