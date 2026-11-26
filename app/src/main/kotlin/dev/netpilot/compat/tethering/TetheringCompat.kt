package dev.netpilot.compat.tethering

import android.content.Context
import android.net.Network
import dev.netpilot.core.model.AdapterMode
import dev.netpilot.core.model.AppConfig
import dev.netpilot.core.root.RootCommandExecutor

const val TYPE_WIFI = 0
const val TYPE_USB = 1

data class TetherOperation(val accepted: Boolean, val code: String? = null, val detail: String = "", val outcomeUncertain: Boolean = false)
data class TetherObservation(
    val interfacesByType: Map<Int, List<String>> = emptyMap(),
    val upstream: Network? = null,
    val upstreamObserved: Boolean = false,
    val clientAddressCounts: Map<Int, Int> = emptyMap(),
    val errors: Map<String, Int> = emptyMap(),
    val eventsRegistered: Boolean = false,
    val observationError: String? = null,
)

interface TetheringCompat : AutoCloseable {
    val name: String
    fun start(type: Int): TetherOperation
    fun stop(type: Int): TetherOperation
    fun observation(): TetherObservation
    fun refresh()
}

fun selectTetheringAdapter(context: Context, config: AppConfig, executor: RootCommandExecutor, onChange: () -> Unit): TetheringCompat {
    val native = when (config.tetheringAdapter) {
        AdapterMode.API_30 -> TetheringApi30Adapter(context, onChange)
        AdapterMode.API_31 -> TetheringApi31Adapter(context, onChange)
        AdapterMode.API_36 -> if (android.os.Build.VERSION.SDK_INT >= 36) TetheringApi36Adapter(context, onChange) else TetheringApi30Adapter(context, onChange)
        else -> when { android.os.Build.VERSION.SDK_INT >= 36 -> TetheringApi36Adapter(context, onChange); android.os.Build.VERSION.SDK_INT >= 31 -> TetheringApi31Adapter(context, onChange); else -> TetheringApi30Adapter(context, onChange) }
    }
    return RootTetheringAdapter(native, OemTetheringAdapter(context, config, executor), config)
}
