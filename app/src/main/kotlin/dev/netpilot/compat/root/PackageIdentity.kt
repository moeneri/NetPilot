package dev.netpilot.compat.root

import android.content.Context
import android.content.pm.PackageManager
import dev.netpilot.core.model.RootInstallIdentity
import java.security.MessageDigest

object PackageIdentity {
    private const val PACKAGE = "dev.netpilot"
    fun current(context: Context): RootInstallIdentity {
        val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: error("系统未提供应用签名信息")
        val signatures = signing.apkContentsSigners ?: error("应用签名不可用")
        return RootInstallIdentity(context.packageManager.getPackageUid(PACKAGE, 0), signatures.map { digest(it.toByteArray()) }.toSet())
    }
    fun matches(context: Context, expected: RootInstallIdentity): Boolean = runCatching {
        if (expected.appUid <= 0 || expected.signerDigests.isEmpty() || expected.signerDigests.any { !it.matches(Regex("[a-f0-9]{64}")) }) return@runCatching false
        val current = current(context)
        if (current.appUid != expected.appUid) return@runCatching false
        if (current.signerDigests == expected.signerDigests) return@runCatching true
        val signing = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return@runCatching false
        !signing.hasMultipleSigners() && signing.signingCertificateHistory.orEmpty().map { digest(it.toByteArray()) }.containsAll(expected.signerDigests)
    }.getOrDefault(false)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
