package com.shilapi.xcertplay

import android.content.Context
import android.os.Build

/** Preferences stay local to each Android device; successful video confirms a recovery point. */
internal data class F10DisplayProfile(
    val scale: Int, val fps: Int, val hevc: Boolean, val softwareHevc: Boolean,
    val uiScale: Int, val widthMm: Int,
) {
    fun apply(context: Context) {
        AirPlayPersistence.saveDisplayScaleTenths(context, scale)
        AirPlayPersistence.saveFps(context, fps)
        AirPlayPersistence.saveHevcEnabled(context, hevc)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, softwareHevc)
        AirPlayPersistence.saveUiScalePercent(context, uiScale)
        AirPlayPersistence.saveWidthPhysicalMm(context, widthMm)
    }

    fun markWorking(context: Context) {
        context.getSharedPreferences("f10_working_display", Context.MODE_PRIVATE).edit()
            .putInt("scale", scale).putInt("fps", fps).putBoolean("hevc", hevc)
            .putBoolean("software", softwareHevc).putInt("ui", uiScale).putInt("width", widthMm).apply()
    }

    companion object {
        fun current(context: Context) = F10DisplayProfile(AirPlayPersistence.loadDisplayScaleTenths(context),
            AirPlayPersistence.loadFps(context), AirPlayPersistence.loadHevcEnabled(context),
            AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context), AirPlayPersistence.loadUiScalePercent(context),
            AirPlayPersistence.loadWidthPhysicalMm(context))

        fun working(context: Context): F10DisplayProfile? {
            val p = context.getSharedPreferences("f10_working_display", Context.MODE_PRIVATE)
            if (!p.contains("scale")) return null
            return F10DisplayProfile(p.getInt("scale", 7), p.getInt("fps", 30), p.getBoolean("hevc", false),
                p.getBoolean("software", false), p.getInt("ui", 100), p.getInt("width", current(context).widthMm))
        }

        fun recommended(context: Context) = current(context).copy(
            scale = if (Build.VERSION.SDK_INT >= 31) 10 else 7,
            fps = if (Build.VERSION.SDK_INT >= 31) 60 else 30, hevc = false, softwareHevc = false)
    }
}
