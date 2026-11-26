package dev.netpilot.compat.android

import android.os.Build

object AndroidVersionDetector {
    val api: Int get() = Build.VERSION.SDK_INT
    val isSupported: Boolean get() = api >= 30
    val adapterGeneration: Int get() = when { api >= 36 -> 36; api >= 31 -> 31; else -> 30 }
    val description: String get() = "Android ${Build.VERSION.RELEASE} · API $api"
}
