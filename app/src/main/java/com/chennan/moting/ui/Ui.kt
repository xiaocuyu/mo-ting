package com.chennan.moting.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

fun Context.dp(value: Number): Int =
    (value.toFloat() * resources.displayMetrics.density).roundToInt().coerceAtLeast(0)

fun Context.sp(value: Number): Float =
    value.toFloat() * resources.displayMetrics.scaledDensity

fun View.setHorizontalPadding(value: Int) {
    setPadding(value, paddingTop, value, paddingBottom)
}

fun View.setMargins(left: Int, top: Int, right: Int, bottom: Int) {
    val params = layoutParams as? LinearLayout.LayoutParams ?: return
    params.setMargins(left, top, right, bottom)
    layoutParams = params
}

fun TextView.setPillBackground() {
    setBackgroundResource(com.chennan.moting.R.drawable.bg_chip)
}

/** 统一设置状态栏 / 导航栏颜色，并控制图标明暗。 */
fun Activity.applySystemBars(color: Int, lightIcons: Boolean) {
    window.statusBarColor = color
    window.navigationBarColor = color
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = window.insetsController
        if (controller != null) {
            val appearance = if (lightIcons) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            }
            controller.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    } else {
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        flags = if (lightIcons) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }
}

fun Activity.enableEdgeToEdgeSoft(): Unit {
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
}

fun blend(color: Int, alpha: Float): Int = Color.argb(
    (alpha * 255).roundToInt().coerceIn(0, 255),
    Color.red(color),
    Color.green(color),
    Color.blue(color),
)
