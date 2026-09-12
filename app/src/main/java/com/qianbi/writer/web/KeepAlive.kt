package com.qianbi.writer.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** 电池优化相关的小工具：Android 杀后台的头号原因就是没关电池优化。 */
object KeepAlive {

    /** 已经关了电池优化（也就是「允许后台运行」）返回 true。 */
    fun ignoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return try {
            manager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            true
        }
    }

    /** 弹系统询问框；国内 ROM 常常没有这个页面，失败就退回应用详情页。 */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(direct)
            true
        } catch (e: Exception) {
            openAppDetails(context)
        }
    }

    /** 兜底：跳到本应用的系统设置页，让用户自己找「电池 / 后台限制」。 */
    fun openAppDetails(context: Context): Boolean = try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        false
    }
}
