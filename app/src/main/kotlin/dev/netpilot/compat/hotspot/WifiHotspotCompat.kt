package dev.netpilot.compat.hotspot

import dev.netpilot.compat.tethering.TYPE_WIFI
import dev.netpilot.compat.tethering.TetherOperation
import dev.netpilot.compat.tethering.TetheringCompat

interface WifiHotspotCompat {
    fun startInternetTethering(): TetherOperation
    fun stopInternetTethering(): TetherOperation
}
class WifiTetheringAdapter(private val tethering: TetheringCompat) : WifiHotspotCompat {
    override fun startInternetTethering() = tethering.start(TYPE_WIFI)
    override fun stopInternetTethering() = tethering.stop(TYPE_WIFI)
}
class RootHotspotAdapter(private val native: WifiTetheringAdapter) : WifiHotspotCompat by native
