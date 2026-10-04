// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A responsive connection console for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var connectionReturnPage = "home"
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var hotspotInfo: TextView? = null
    private var audioButton: Button? = null
    private var returningToConnect = false
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var adbStatus: TextView? = null
    private var adbCheckGeneration = 0
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            render()
            if (pendingWireless) choosePhone()
        } else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !nightMode
            isAppearanceLightNavigationBars = !nightMode
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        connectionReturnPage = savedInstanceState?.getString("connection_return") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { goBack(); render() }
                else CarPlayBackgroundSession.stop { runOnUiThread { finish() } }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); outState.putString("connection_return", connectionReturnPage); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume()
        navigatingToScreen = false
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        if (returningToConnect) {
            returningToConnect = false
            val radioReady = if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.WIFI_P2P)
                applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true
                else !carHotspotOff()
            if (radioReady) handler.post { connect(true) }
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch) render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null &&
                (!AirPlayPersistence.loadWirelessEnabled(this) || DiPlayPreferences.phoneAddress(this) != null)) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private var navigatingToScreen = false
    override fun startActivity(intent: Intent) {
        navigatingToScreen = true
        super.startActivity(intent)
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!navigatingToScreen) CarPlayBackgroundSession.stop()
    }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        hotspotInfo = null; audioButton = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = true }
        // Android 15+ enforces edge-to-edge; fitSystemWindows alone no longer reserves these areas.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val content = column().apply { setPadding(dp(if (wideLayout) 28 else 20), dp(12), dp(if (wideLayout) 28 else 20), dp(24)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_f10_play); contentDescription = getString(R.string.app_name) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(R.string.diplay), 24, TEXT, true).apply { setPadding(dp(12), 0, 0, 0); minHeight = dp(56) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
            if (page == "home") CarPlayBackgroundSession.stop { runOnUiThread {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } }
            else { goBack(); render() }
        }.apply {
            background = ripple(BG, BG)
            setTextColor(ACCENT)
            textSize = 16f
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }, LinearLayout.LayoutParams(-2, -2))
        content.addView(header)
        content.addView(space(if (shortLayout) 12 else 22))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settings(content)
            "about" -> about(content)
            else -> if (page.startsWith("settings-")) settings(content, page.removePrefix("settings-")) else home(content)
        }
        setContentView(scroll)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val wide = wideLayout
        val phones = card().apply {
            addView(label(getString(R.string.f10_your_phones), 20, TEXT, true).apply {
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(space(12))
        }
        val paired = pairedIPhones()
        val list = column()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            list.addView(button(getString(R.string.f10_show_paired_iphones), false) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }, matchButton(0))
        } else if (runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false }.getOrDefault(false)) {
            list.addView(button(getString(R.string.drive_enable_bluetooth), false) {
                openSystem(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }, matchButton())
        } else if (paired.isEmpty()) {
            list.addView(label(getString(R.string.f10_no_paired_iphone), 16, MUTED), matchButton(0))
        } else paired.forEach { device ->
            val selected = device.address.equals(DiPlayPreferences.phoneAddress(this), true)
            val name = device.name ?: getString(R.string.paired_device)
            val display = if (paired.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            list.addView(button(display, false) { selectPhone(device, true) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isSelected = selected
                background = ripple(if (selected) SELECTED else SURFACE, if (selected) SELECTED else BORDER)
                if (selected) setTextColor(ACCENT)
                contentDescription = if (selected) getString(R.string.f10_selected_phone, display) else display
                maxLines = 3
            }, matchButton(6))
        }
        phones.addView(list)
        phones.addView(tile(getString(R.string.f10_pair_device), R.drawable.ic_drive_pair, false) {
            CarPlayBackgroundSession.stop { runOnUiThread { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
        }.apply { textSize = 16f; setTextColor(ACCENT); background = ripple(SURFACE, BORDER) }, matchButton(12))
        if (wide && !shortLayout) {
        phones.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(14) })
        phones.addView(label(getString(R.string.f10_hotspot), 13, MUTED))
        hotspotInfo = label("", 15, TEXT).also { phones.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
        }

        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(true)
        }.apply {
            textSize = 18f
            minHeight = dp(56)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        disconnectButton = tile(getString(R.string.disconnect), R.drawable.ic_drive_disconnect, false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }
        audioButton = tile("", R.drawable.ic_drive_audio, false) { AudioOutputPicker.show(this) { refreshStatus() } }
        val actions = listOf(audioButton,
            tile(getString(if (shortLayout || !wide) R.string.drive_network_short else R.string.f10_network_details), R.drawable.ic_dp_connection, false) { showHotspotDetails() },
            tile(getString(R.string.settings), R.drawable.ic_drive_settings, false) { page = "settings"; render() }, disconnectButton)
        val connectionCard = card().apply {
            background = rounded(SELECTED, SELECTED)
            setPadding(dp(20), dp(if (shortLayout) 16 else 24), dp(20), dp(if (shortLayout) 16 else 24))
            val summary = column().apply {
                addView(label(getString(R.string.carplay), if (shortLayout) 22 else 28, TEXT, true).apply {
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                })
                status = label("", 14, MUTED).also {
                    addView(it, matchButton(6))
                    it.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
            }
            if (shortLayout && wide) {
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(connectButton, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginStart = dp(16) })
                })
            } else {
                addView(summary)
                addView(connectButton, matchButton(20))
            }
        }
        val controls = column().apply {
            addView(connectionCard)
            actions.chunked(if (resources.configuration.screenWidthDp >= 360 && resources.configuration.fontScale <= 1.3f) 2 else 1).forEach { tiles ->
                addView(row().apply {
                    tiles.forEachIndexed { index, tile ->
                        tile?.minHeight = dp(if (shortLayout) 60 else if (wide) 76 else 64)
                        tile?.textSize = 16f
                        tile?.background = ripple(SURFACE, SURFACE)
                        addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(12) })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
        }

        if (wide) content.addView(row().apply {
            gravity = Gravity.TOP
            addView(phones, LinearLayout.LayoutParams(0, -2, 1f))
            addView(controls, LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginStart = dp(24) })
        }) else {
            content.addView(phones)
            content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        setupError?.let { content.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
    }

    private fun pairedIPhones(): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val bonded = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        val selected = DiPlayPreferences.phoneAddress(this)
        return bonded.filter { it.address.equals(selected, true) ||
            it.bluetoothClass?.majorDeviceClass != android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
        }.sortedWith(compareByDescending<BluetoothDevice> { it.address.equals(selected, true) }
            .thenByDescending { it.name?.contains("iPhone", true) == true }.thenBy { it.name ?: "" })
    }

    private fun showHotspotDetails() {
        val manual = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val ssid = CarPlayBackgroundSession.hotspot?.ssid
            ?: AirPlayPersistence.loadManualHotspotSsid(this).takeIf { manual }
        val message = buildString {
            append(ssid ?: getString(R.string.f10_hotspot_waiting))
            CarPlayBackgroundSession.hotspot?.let { append("\n${it.band} · ${it.address}") }
            if (manual) append("\n\n").append(getString(R.string.f10_password))
                .append(": ").append(AirPlayPersistence.loadManualHotspotPassphrase(this@DiPlayActivity))
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.f10_hotspot)).setMessage(message)
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.settings)) { _, _ -> openCarWifiSettings() }.show()
    }

    private fun settings(content: LinearLayout, category: String? = null) {
        if (category == null) {
            if (shortLayout) {
                content.addView(label(getString(R.string.settings), 24, TEXT, true))
                content.addView(space(12))
            } else pageHeading(content, getString(R.string.settings), getString(R.string.f10_settings_hint))
            val entries = mutableListOf(
                Triple("connection", R.string.connection_setup, R.drawable.ic_dp_connection),
                Triple("display", R.string.display_and_performance, R.drawable.ic_dp_display),
                Triple("audio", R.string.audio_routing, R.drawable.ic_drive_audio),
                Triple("widgets", R.string.f10_widgets_language, R.drawable.ic_drive_widgets),
                Triple("privacy", R.string.f10_privacy, R.drawable.ic_dp_permissions),
                Triple("support", R.string.f10_support, R.drawable.ic_dp_diagnostics),
            )
            if (BydOutputSettings.available(this)) entries.add(Triple("vehicle", R.string.byd_navigation, R.drawable.ic_dp_navigation))
            val hints = mapOf(
                "connection" to R.string.f10_connection_hint, "display" to R.string.f10_display_hint,
                "audio" to R.string.f10_audio_menu_hint, "widgets" to R.string.f10_widgets_hint,
                "privacy" to R.string.f10_privacy_hint, "support" to R.string.f10_support_hint,
                "vehicle" to R.string.f10_vehicle_hint,
            )
            entries.chunked(if (shortLayout && wideLayout && resources.configuration.fontScale <= 1.3f) 3 else if (wideLayout) 2 else 1).forEach { entriesInRow ->
                content.addView(row().apply {
                    entriesInRow.forEachIndexed { index, (key, title, icon) ->
                        addView(menuEntry(getString(title), getString(hints.getValue(key)), icon, showHint = !shortLayout) {
                            page = "settings-$key"; render()
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(12) })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
            return
        }
        when (category) {
            "connection" -> {
                section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
                    card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
                    card.addView(button(getString(R.string.open_connection_setup), false) { connectionReturnPage = page; page = "connection"; render() }, matchButton(12))
                }
                section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
                    toggle(card, getString(R.string.f10_auto_wireless), getString(R.string.f10_auto_wireless_hint), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
                    toggle(card, getString(R.string.open_after_the_car_starts), getString(R.string.availability_depends_on_your_head_unit_s_startup_settings), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
                    card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12))
                }
                content.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(0))
            }
            "display" -> {
                section(content, getString(R.string.f10_lyrics_title)) { card ->
                    toggle(card, getString(R.string.f10_lyrics_show_panel), getString(R.string.f10_lyrics_setting_hint),
                        DiPlayPreferences.lyricsEnabled(this)) { DiPlayPreferences.saveLyricsEnabled(this, it) }
                    card.addView(label(getString(R.string.f10_lyrics_provider_hint), 14, MUTED))
                }
                section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
                    card.addView(label(getString(if (Build.VERSION.SDK_INT >= 31) R.string.drive_modern_preset_hint else R.string.f10_performance_hint), 14, MUTED))
                    card.addView(label(getString(R.string.f10_display_saved_local), 14, MUTED))
                    card.addView(button(getString(R.string.f10_profile_recommended, Build.MODEL), false) {
                        F10DisplayProfile.recommended(this).apply(this)
                        toast(getString(R.string.saved_for_your_next_connection)); render()
                    }, matchButton(10))
                    card.addView(button(getString(R.string.f10_profile_light), false) {
                        F10DisplayProfile.current(this).copy(scale = 7, fps = 30, hevc = false, softwareHevc = false).apply(this)
                        toast(getString(R.string.saved_for_your_next_connection)); render()
                    }, matchButton(10))
                    card.addView(button(getString(R.string.f10_profile_smooth), false) {
                        F10DisplayProfile.current(this).copy(scale = 10, fps = 60, hevc = false, softwareHevc = false).apply(this)
                        toast(getString(R.string.saved_for_your_next_connection)); render()
                    }, matchButton(10))
                    card.addView(button(getString(R.string.f10_restore_display), false) {
                        F10DisplayProfile.working(this)?.apply(this)
                        toast(getString(R.string.saved_for_your_next_connection)); render()
                    }.apply { isEnabled = F10DisplayProfile.working(this@DiPlayActivity) != null }, matchButton(10))
                    if (F10DisplayProfile.working(this) == null) card.addView(label(getString(R.string.f10_no_working_display), 14, MUTED))
                    card.addView(space(12))
                    carPlaySizeControl(card)
                    val scales = listOf(10, 8, 7, 6)
                    choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.f10_70_balanced), getString(R.string.s_60_lightest_load)), scales.indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, scales[it]) }
                    choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
                    toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
                    toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
                    toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                        AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
                    }
                }
            }
            "audio" -> {
                content.addView(menuEntry(getString(R.string.f10_audio_title), AudioOutputPicker.status(this), R.drawable.ic_drive_audio) {
                    AudioOutputPicker.show(this) { render() }
                }, matchButton(0))
                content.addView(space(16))
                section(content, getString(R.string.audio_routing)) { card ->
                    toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
                    if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                        toggle(card, getString(R.string.advanced_audio_channel_mapping),
                            getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                            AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                            AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                        }
                    }
                    val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
                    choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                        bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
                    }
                    mediaChannelControl(card)
                    navigationChannelControl(card)
                }
            }
            "widgets" -> {
                pageHeading(content, getString(R.string.f10_widgets_language), getString(R.string.f10_widgets_hint))
                content.addView(menuEntry(getString(R.string.f10_add_widget), getString(R.string.f10_widget_description), R.drawable.ic_dp_display) {
                    DiPlayWidget.requestPin(this)
                }, matchButton(0))
                content.addView(menuEntry(getString(R.string.f10_add_navigation_widget), getString(R.string.navigation_widget_description), R.drawable.ic_dp_navigation) {
                    DiPlayWidget.requestPin(this, NavigationWidget::class.java)
                }, matchButton(12))
                content.addView(space(20))
                languageSettings(content)
            }
            "privacy" -> {
                section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
                    toggle(card, getString(R.string.report_location_to_iphone),
                        getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                        AirPlayPersistence.loadLocationReportingEnabled(this)) {
                        AirPlayPersistence.saveLocationReportingEnabled(this, it)
                        if (it && !hasPreciseLocation()) {
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else {
                            reconnectForLocation()
                        }
                    }
                }
                content.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(0))
            }
            "support" -> {
                section(content, getString(R.string.f10_connection_help), R.drawable.ic_dp_permissions) { card ->
                    card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
                    card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10))
                    card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10))
                }
                section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
                    card.addView(button(getString(R.string.f10_health), false) { ConnectionHealth.show(this) }, matchButton(10))
                    exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                        else chooseReportDestination()
                    }.apply { isEnabled = !exportInProgress }
                    card.addView(exportButton, matchButton(10))
                    card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10))
                    val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
                    card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
                }
                section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
                    card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0))
                }
            }
            "vehicle" -> {
                if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
                    toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                        getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                        com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
                    if (ClusterMapPresentation.findDisplay(this) != null) {
                        toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                            getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                            AirPlayPersistence.loadClusterMapEnabled(this)) {
                            AirPlayPersistence.saveClusterMapEnabled(this, it)
                            reconnectForClusterMap()
                        }
                toggle(card, getString(R.string.center_map_card), getString(R.string.center_map_card_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_usage_allowed)
                        else getString(R.string.center_map_usage_missing, packageName), 14, if (usage) MUTED else WARNING))
                }
                        if (DiLink51ClusterLayout.supported()) {
                            val automatic = DiLink51ClusterLayout.automatic(this)
                            toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                                getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                                DiLink51ClusterLayout.saveAutomatic(this, it)
                                render()
                                reconnectForClusterMap()
                            }
                            val allowed = DiLink51ClusterMonitor.hasAccess(this)
                            card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                                else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                            card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10))
                            if (!automatic) {
                                val themes = DiLink51ClusterLayout.Theme.entries
                                choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                                    DiLink51ClusterLayout.saveTheme(this, themes[it])
                                    reconnectForClusterMap()
                                }
                                card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                            }
                            val contrasts = DiLink51ClusterLayout.Contrast.entries
                            choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                                DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                                reconnectForClusterMap()
                            }
                        } else {
                            val sizes = CarPlayClusterDisplay.scalePresets
                            val contents = CarPlayClusterDisplay.Content.entries
                            val content = AirPlayPersistence.loadClusterContent(this)
                            choice(card, getString(R.string.dashboard_shows), listOf(
                                getString(R.string.dashboard_content_map),
                                getString(R.string.dashboard_content_turn_card),
                                getString(R.string.dashboard_content_map_with_turn_card),
                            ), contents.indexOf(content)) {
                                AirPlayPersistence.saveClusterContent(this, contents[it])
                                render()
                            }
                            // Both contents share the same safe area and position controls.
                            val turnCard = content == CarPlayClusterDisplay.Content.TURN_CARD
                            choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                                listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                                sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                            }
                            val across = CarPlayClusterDisplay.horizontalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                                across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                            }
                            val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                            choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                                upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                            }
                            card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                                AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                                AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                                render()
                                reconnectForClusterMap()
                            }, matchButton(10))
                            toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                                getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                                BydOutputSettings.clusterStreamPause(this)) {
                                BydOutputSettings.setClusterStreamPause(this, it)
                                if (it) checkAdbAccess(mayAsk = true)
                            }
                        }
                    }
                    toggle(card, getString(R.string.car_battery_for_the_iphone),
                        getString(R.string.car_battery_for_the_iphone_description),
                        BydOutputSettings.batteryToIphone(this)) {
                        BydOutputSettings.setBatteryToIphone(this, it)
                        if (it) {
                            checkAdbAccess(mayAsk = true, reconnectWhenReady = CarPlayBackgroundSession.hasSession())
                        } else if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                    val connectors = EvChargingConnectors.entries
                    choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                        connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                        BydOutputSettings.setChargingConnectors(this, connectors[it])
                    }
                    val lowCharge = BydOutputSettings.lowChargePresets
                    choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                            getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT) R.string.percent_default else R.string.percent_value, it)
                        },
                        lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                        BydOutputSettings.setLowChargePercent(this, lowCharge[it])
                    }
                    toggle(card, getString(R.string.wheel_speed_for_tunnels),
                        getString(R.string.wheel_speed_for_tunnels_description),
                        BydOutputSettings.wheelSpeedToIphone(this)) {
                        BydOutputSettings.setWheelSpeedToIphone(this, it)
                        if (it) checkAdbAccess(mayAsk = true)
                        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                    }
                    toggle(card, getString(R.string.video_while_parked),
                        getString(R.string.video_while_parked_description),
                        BydOutputSettings.videoWhileParked(this)) {
                        BydOutputSettings.setVideoWhileParked(this, it)
                        if (it) checkAdbAccess(mayAsk = true)
                        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                    }
            toggle(card, getString(R.string.cluster_song),
                getString(R.string.cluster_song_description),
                BydOutputSettings.clusterSong(this)) {
                BydOutputSettings.setClusterSong(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                BydNavigationOutputs.clusterSongChanged(it)
            }
                    adbStatus = label("", 14, MUTED).also { status ->
                        card.addView(status)
                    }
                    if (BydOutputSettings.clusterStreamPause(this) || BydOutputSettings.batteryToIphone(this) ||
                        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
                        checkAdbAccess(mayAsk = false)
                    card.addView(button(getString(R.string.check_adb_access), false) { checkAdbAccess(mayAsk = true) }, matchButton(10))
                    card.addView(button(getString(R.string.apply_and_reconnect), false) {
                        if (BydOutputSettings.batteryToIphone(this)) {
                            checkAdbAccess(mayAsk = true, reconnectWhenReady = true)
                        } else {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }, matchButton(10))
                }
            }
        }
    }

    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> returningToConnect = true; openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12))
        }
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    // The car's approval dialog for DiPlay's ADB key opens only from here, never while driving.
    private fun checkAdbAccess(mayAsk: Boolean, reconnectWhenReady: Boolean = false) {
        val status = adbStatus ?: return
        val generation = ++adbCheckGeneration
        status.setTextColor(MUTED)
        status.text = getString(if (mayAsk) R.string.adb_checking_may_ask else R.string.adb_checking)
        Thread({
            val result = runCatching { BydAdbAccess.check(applicationContext, mayAsk) }.getOrNull()
            runOnUiThread {
                if (adbStatus !== status || generation != adbCheckGeneration || isFinishing || isDestroyed) return@runOnUiThread
                status.setTextColor(if (result?.state == BydAdbAccess.State.READY) MUTED else WARNING)
                status.text = adbStatusText(result)
                if (reconnectWhenReady && BydOutputSettings.batteryToIphone(this) &&
                    result?.state == BydAdbAccess.State.READY && result.batteryPercent != null) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
        }, "diplay-adb-check").start()
    }

    private fun adbStatusText(result: BydAdbAccess.Status?): String = when (result?.state) {
        null -> getString(R.string.adb_check_failed)
        BydAdbAccess.State.READY -> listOfNotNull(
            getString(R.string.adb_access_ready),
            result.dashboardMode?.let {
                getString(if (result.dashboardShowsMap) R.string.adb_dashboard_sends_map else R.string.adb_dashboard_does_not_send_map,
                    it.localizedLabel(this))
            },
            result.batteryPercent?.let { getString(R.string.adb_battery_reading, it.roundToInt(), result.rangeKm ?: 0) }
                ?: getString(R.string.adb_battery_unreadable).takeIf { BydOutputSettings.batteryToIphone(this) },
            getString(R.string.adb_battery_reconnect).takeIf {
                BydOutputSettings.batteryToIphone(this) && result.batteryPercent != null
            },
        ).joinToString(" ")
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.WIFI_P2P) {
            val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (wifi?.isWifiEnabled == false) {
                toast(getString(R.string.f10_wifi_needed))
                returningToConnect = true
                openSystem(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                return
            }
        }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                selectPhone(devices[index], pendingWireless || CarPlayBackgroundSession.hasSession())
            }.setOnCancelListener { pendingWireless = false }
            .setNeutralButton(getString(R.string.pair_another)) { _, _ ->
                pendingWireless = false
                CarPlayBackgroundSession.stop { runOnUiThread { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun selectPhone(device: BluetoothDevice, start: Boolean) {
        pendingWireless = false
        if (device.address.equals(DiPlayPreferences.phoneAddress(this), true) && CarPlayBackgroundSession.hasSession()) {
            openProjection()
            return
        }
        CarPlayBackgroundSession.stop { runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                render()
                if (start) connect(true)
            }
        } }
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false }.getOrDefault(false) -> getString(R.string.drive_bluetooth_off)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.isEnabled = running
            disconnectButton?.alpha = if (running) 1f else 0.45f
            lastRunning = running
        }
        val canConnect = setupError == null && (running || DiPlayPreferences.phoneAddress(this) != null)
        connectButton?.isEnabled = canConnect
        connectButton?.alpha = if (canConnect) 1f else 0.5f
        val network = CarPlayBackgroundSession.hotspot
        hotspotInfo?.text = network?.let { "${it.ssid}\n${it.band}" } ?: when {
            AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL ->
                "${AirPlayPersistence.loadManualHotspotSsid(this).ifBlank { getString(R.string.f10_not_configured) }}\n" +
                    getString(if (carHotspotOff()) R.string.f10_hotspot_off else R.string.f10_hotspot_saved)
            else -> getString(R.string.f10_hotspot_waiting)
        }
        audioButton?.text = if (com.shilapi.xcertplay.media.AudioOutput.load(this) == com.shilapi.xcertplay.media.AudioOutput.SYSTEM) getString(R.string.f10_audio_auto) else getString(R.string.f10_audio_label, AudioOutputPicker.label(this))
    }
    private fun reportFileName() = "F10-Play-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("F10 Play ${version()} · private beta diagnostic report")
                    appendLine(ConnectionHealth.report(appContext))
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) "Downloads/F10 Play/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) ON_ACCENT else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x336F9FD9),
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = ((resources.configuration.screenWidthDp - 80) / (56 * resources.configuration.fontScale)).toInt().coerceIn(3, 7)
            rowCount = (21 + columnCount - 1) / columnCount
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                minHeight = dp(56)
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = -2
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, if (shortLayout) 16 else 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = menuEntry(title, options[selection], null) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        } else render()
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0))
    }
    private fun goBack() {
        page = when {
            page.startsWith("settings-") -> "settings"
            page == "about" -> "settings-support"
            page == "connection" -> connectionReturnPage
            else -> "home"
        }
    }

    private fun pageHeading(parent: LinearLayout, title: String, hint: String) {
        parent.addView(label(title, 34, TEXT, true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        parent.addView(label(hint, 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
    }

    private fun ripple(color: Int, stroke: Int) = android.graphics.drawable.RippleDrawable(
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(ACCENT and 0x00ffffff or 0x55000000, ACCENT and 0x00ffffff or 0x22000000)), rounded(color, stroke), null)

    private fun tile(title: String, icon: Int, primary: Boolean, click: () -> Unit) = button(title, primary, click).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val drawable = getDrawable(icon)?.mutate()?.apply {
            setTint(if (primary) ON_ACCENT else ACCENT); setBounds(0, 0, dp(26), dp(26))
        }
        setCompoundDrawables(drawable, null, null, null)
        compoundDrawablePadding = dp(14)
        textSize = 18f
        maxLines = 4
    }

    private fun menuEntry(title: String, hint: String, icon: Int?, showHint: Boolean = true, click: () -> Unit) = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ripple(SURFACE, SURFACE)
        minimumHeight = dp(if (showHint) 88 else 64)
        isFocusable = true; isClickable = true
        contentDescription = "$title. $hint"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (icon != null) addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = rounded(SELECTED, SELECTED)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(label(title, if (shortLayout) 16 else 18, TEXT, true))
            if (showHint) addView(label(hint, 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("›", 26, MUTED).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(20), -2).apply { marginStart = dp(12) })
        setOnClickListener { click() }
    }

    private fun card() = column().apply { background = rounded(SURFACE, SURFACE); setPadding(dp(20), dp(20), dp(20), dp(20)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 16f; setTextColor(if (primary) ON_ACCENT else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = ripple(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER)
        setPadding(dp(18), dp(16), dp(18), dp(16)); minHeight = dp(56); minimumWidth = 0; stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat(); if (color != stroke) setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val shortLayout: Boolean get() = resources.configuration.screenHeightDp < 450
    private val wideLayout: Boolean get() = resources.configuration.screenWidthDp >= 650 && resources.configuration.fontScale < 1.6f
    private val nightMode: Boolean get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val BG get() = getColor(R.color.drive_background)
    private val SURFACE get() = getColor(R.color.drive_surface)
    private val BORDER get() = getColor(R.color.drive_border)
    private val ACCENT get() = getColor(R.color.drive_accent)
    private val ON_ACCENT get() = getColor(R.color.drive_on_accent)
    private val SELECTED get() = getColor(R.color.drive_selected)
    private val TEXT get() = getColor(R.color.drive_text)
    private val MUTED get() = getColor(R.color.drive_secondary)
    private val WARNING get() = getColor(R.color.drive_warning)
}
