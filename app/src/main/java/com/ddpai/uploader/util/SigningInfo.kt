package com.ddpai.uploader.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SigningInfo {
    fun packageName(ctx: Context): String = ctx.packageName

    fun signingSha1(ctx: Context): String = try {
        val pm = ctx.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return "unavailable"
        val digest = MessageDigest.getInstance("SHA-1").digest(cert)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        "unavailable"
    }
}
