package com.nextflow.nftouch.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 采集设备信息：品牌/型号/分辨率/电量。
 */
object DeviceInfo {

    // 触摸坐标系的实际尺寸（用于坐标转换）
    @Volatile var touchW = 720
    @Volatile var touchH = 1600

    fun collect(context: Context): Map<String, Any> {
        return try {
            val brand = Build.BRAND ?: ""
            val model = Build.MODEL ?: ""
            val res = getRealResolution(context)
            touchW = res.first
            touchH = res.second
            val battery = getBattery(context)

            mapOf(
                "brand" to brand,
                "model" to model,
                "resolution" to "${touchW}x${touchH}",
                "battery" to battery
            )
        } catch (e: Exception) {
            mapOf(
                "brand" to (Build.BRAND ?: ""),
                "model" to (Build.MODEL ?: ""),
                "resolution" to "0x0",
                "battery" to 0
            )
        }
    }

    /** 获取真实的物理显示尺寸（使用 Display.getRealSize） */
    private fun getRealResolution(context: Context): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 720 to 1600
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val point = Point()
                wm.defaultDisplay.getRealSize(point)
                point.x to point.y
            }
        } catch (e: Exception) {
            720 to 1600
        }
    }

    private fun getBattery(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) (level * 100 / scale) else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }
}
