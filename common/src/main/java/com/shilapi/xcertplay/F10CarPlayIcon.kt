package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream

internal object F10CarPlayIcon {
    fun defaultBitmap(context: Context): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        requireNotNull(context.getDrawable(R.drawable.ic_f10_play)).apply {
            setBounds(0, 0, bitmap.width, bitmap.height)
            draw(Canvas(bitmap))
        }
        return bitmap
    }

    fun load(context: Context): AirPlayIcon {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(context)
        val bitmap = custom?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: defaultBitmap(context)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        return AirPlayIcon(bitmap.width, bitmap.height, bytes).also { bitmap.recycle() }
    }
}
