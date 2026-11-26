package dev.netpilot.compat.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.FileObserver
import dev.netpilot.compat.android.CapabilityDetector
import dev.netpilot.compat.android.RootBroadcastCompat
import dev.netpilot.compat.android.ReceiverRegistration
import dev.netpilot.core.model.UsbSignals
import dev.netpilot.core.model.OemSettings
import java.io.File

const val USB_STATE_ACTION = "android.hardware.usb.action.USB_STATE"

interface UsbCompat : AutoCloseable { fun capture(): UsbSignals }

class UsbBroadcastAdapter(private val context: Context, private val onChange: () -> Unit) : AutoCloseable {
    private val registrations = mutableListOf<ReceiverRegistration>()
    var error: String? = null; private set
    @Volatile var connected: Boolean? = null; private set
    @Volatile var configured: Boolean? = null; private set
    @Volatile var updatedAt: Long = 0; private set
    @Volatile var powerConnected: Boolean = false; private set
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                USB_STATE_ACTION -> {
                    connected = intent.getBooleanExtra("connected", false)
                    configured = intent.getBooleanExtra("configured", false)
                    updatedAt = System.currentTimeMillis()
                }
                Intent.ACTION_BATTERY_CHANGED -> { powerConnected = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 }
            }
            onChange()
        }
    }
    init {
        // A registration returns only one sticky Intent. Request each initial state separately.
        listOf(USB_STATE_ACTION, Intent.ACTION_BATTERY_CHANGED).forEach { action ->
            runCatching { RootBroadcastCompat.register(context, receiver, IntentFilter(action), exported = true) }
                .onSuccess { registration ->
                    registrations += registration
                    registration.sticky?.let { receiver.onReceive(context, it) }
                }.onFailure {
                    val detail = "$action: ${it.cause?.message ?: it.message}"
                    error = listOfNotNull(error, detail).joinToString("; ")
                }
        }
        error = error?.let { "USB 广播不可用：$it；使用系统 USB 服务与 sysfs 兼容扫描" }
    }
    override fun close() { registrations.forEach { it.close() }; registrations.clear() }
}

class UsbSysfsAdapter(private val oem: OemSettings) {
    val paths: List<String> get() = (CapabilityDetector.signalPaths() + oem.usbStatePath.takeIf { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.distinct()
    val rolePaths: List<String> get() = (CapabilityDetector.rolePaths() + oem.dataRolePath.takeIf { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.distinct()
    fun states(): Map<String, String> = paths.mapNotNull { path -> read(path)?.let { path to it } }.toMap()
    fun roles(): Map<String, String> = rolePaths.mapNotNull { path -> read(path)?.let { path to it } }.toMap()
    fun supplyTypes(): Map<String, String> = File("/sys/class/power_supply").listFiles().orEmpty().flatMap { directory -> listOf("usb_type", "real_type").map { File(directory, it) } }.mapNotNull { file -> read(file.path)?.let { file.path to it } }.toMap()
    private fun read(path: String): String? = runCatching { File(path).bufferedReader().use { it.readLine()?.take(256)?.trim() } }.getOrNull()
}

class RootUsbMonitor(private val context: Context, oem: OemSettings, private val onChange: () -> Unit) : UsbCompat {
    private val broadcast = UsbBroadcastAdapter(context, onChange)
    private val sysfs = UsbSysfsAdapter(oem)
    private val observers = (sysfs.paths + sysfs.rolePaths).mapNotNull { path ->
        runCatching { object : FileObserver(File(path), MODIFY or ATTRIB or CLOSE_WRITE) { override fun onEvent(event: Int, path: String?) { onChange() } }.apply { startWatching() } }.getOrNull()
    }
    override fun capture(): UsbSignals {
        val service = UsbServiceAdapter(context).capture()
        val supply = sysfs.supplyTypes()
        return UsbSignals(
            broadcastConnected = broadcast.connected, broadcastConfigured = broadcast.configured,
            broadcastUpdatedAt = broadcast.updatedAt,
            gadgetStates = sysfs.states(), dataRoles = sysfs.roles() + service.dataRoles,
            config = systemProperty("sys.usb.config"), functions = systemProperty("sys.usb.state"),
            powerConnected = broadcast.powerConnected || service.connected == true,
            powerSupplyTypes = supply, servicePortConnected = service.connected, serviceDataEnabled = service.dataEnabled,
            monitorError = broadcast.error, capturedAt = System.currentTimeMillis(),
        )
    }
    override fun close() { broadcast.close(); observers.forEach { it.stopWatching() } }
}

fun systemProperty(name: String): String = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String }.getOrDefault("")
