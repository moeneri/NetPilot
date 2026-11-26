package dev.netpilot.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.netpilot.core.model.MobileStatus
import dev.netpilot.core.model.UpstreamStatus
import dev.netpilot.core.model.UpstreamType
import java.util.concurrent.ConcurrentHashMap

data class NetworkSnapshot(val upstream: UpstreamStatus, val mobile: MobileStatus, val sourceKey: String)

/** The system chooses and routes the tethering upstream; the app observes its actual choice. */
class NetworkUpstreamMonitor(context: Context, private val onChange: () -> Unit) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val availableNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val registrationErrors = mutableListOf<String>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { availableNetworks.add(network); onChange() }
        override fun onLost(network: Network) { availableNetworks.remove(network); onChange() }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = onChange()
    }
    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange()
        override fun onLost(network: Network) = onChange()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = onChange()
    }
    init {
        // No transport filter: Wi-Fi, Ethernet and restricted cellular/DUN networks are observed.
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build(), networkCallback)
        }.onFailure { registrationErrors += "Network callback: ${it.javaClass.simpleName}: ${it.message}" }
        runCatching { connectivity.registerDefaultNetworkCallback(defaultCallback) }
            .onFailure { registrationErrors += "Default network callback: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun status(upstream: Network?, observed: Boolean): UpstreamStatus = capture(upstream, observed).upstream

    fun capture(upstream: Network?, observed: Boolean): NetworkSnapshot {
        val defaultNetwork = connectivity.activeNetwork
        val networks = (availableNetworks.toList() + connectivity.allNetworks.toList() + listOfNotNull(upstream, defaultNetwork)).distinct()
        val capabilities = networks.associateWith { connectivity.getNetworkCapabilities(it) }
        val links = networks.associateWith { connectivity.getLinkProperties(it) }
        fun usable(network: Network) = usable(capabilities[network])
        fun cellular(network: Network) = cellular(capabilities[network])
        fun validated(network: Network?) = capabilities[network]?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val usableNetworks = networks.filter(::usable)
        val confirmed = observed && upstream != null && usable(upstream)
        val candidate = defaultNetwork?.takeIf(::usable) ?: usableNetworks.firstOrNull { validated(it) } ?: usableNetworks.firstOrNull()
        val selected = if (observed && upstream != null) upstream else candidate
        val selectedCapabilities = capabilities[selected]
        val selectedType = type(selectedCapabilities, selected != null)
        val dun = confirmed && selectedCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            selectedCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN) &&
            !selectedCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val monitorError = registrationErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")
        val status = UpstreamStatus(
            available = usableNetworks.isNotEmpty(), validated = validated(selected), tetherUpstreamConfirmed = confirmed,
            type = selectedType, defaultType = type(capabilities[defaultNetwork], defaultNetwork != null),
            defaultNetworkPresent = defaultNetwork != null, networkHandle = selected?.networkHandle,
            interfaceName = links[selected]?.interfaceName, dunUpstream = dun, monitorError = monitorError,
            detail = when {
                !observed -> "等待系统共享状态回调"
                selectedType == UpstreamType.VPN -> "系统共享选择了 VPN 上游；当前未管理其路由"
                dun -> "系统已选择移动共享专用上游（DUN）；客户端联网须由客户端确认"
                confirmed && validated(selected) -> "系统共享上游已就绪；客户端联网须由客户端确认"
                confirmed -> "系统共享上游已确认，等待互联网验证"
                observed && upstream != null -> "系统已报告共享上游，等待网络能力确认"
                usableNetworks.isEmpty() -> "暂未检测到可用上游，等待系统共享选择网络"
                else -> "网络可用，等待系统共享选择上游"
            },
        )
        val cellularNetworks = networks.filter(::cellular)
        val cellularConfirmed = observed && upstream != null && cellular(upstream)
        val cell = if (cellularConfirmed) upstream else cellularNetworks.firstOrNull { validated(it) } ?: cellularNetworks.firstOrNull()
        val legacyMobile = MobileStatus(
            available = cellularNetworks.isNotEmpty(), validated = validated(cell), tetherUpstreamConfirmed = cellularConfirmed,
            defaultIsMobile = defaultNetwork?.let(::cellular) == true, defaultNetworkPresent = defaultNetwork != null,
            dunUpstream = cellularConfirmed && dun, interfaceName = links[cell]?.interfaceName, monitorError = monitorError,
            detail = when {
                cellularConfirmed && dun -> "系统已选择移动共享专用上游（DUN）；客户端联网须由客户端确认"
                cellularConfirmed && validated(cell) -> "原生共享上游已确认为移动数据"
                cellularConfirmed -> "移动数据上游等待互联网验证"
                cell != null -> "移动数据可用，等待共享选择上游"
                else -> "移动数据未就绪"
            },
        )
        // Physical sources drive recovery. Starting/stopping a downstream must not reset its retry budget.
        val sourceKey = usableNetworks.sortedBy { it.networkHandle }.joinToString(";") {
            "${it.networkHandle}:${validated(it)}:${links[it]?.interfaceName}"
        } + ":default=${defaultNetwork?.networkHandle}:${status.defaultType}"
        return NetworkSnapshot(status, legacyMobile, sourceKey)
    }

    fun isMobile(network: Network): Boolean = cellular(connectivity.getNetworkCapabilities(network))
    fun isUsable(network: Network): Boolean = usable(connectivity.getNetworkCapabilities(network))
    fun isVpn(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun usable(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN))
    private fun cellular(capabilities: NetworkCapabilities?): Boolean = usable(capabilities) &&
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    private fun type(capabilities: NetworkCapabilities?, present: Boolean): UpstreamType = when {
        !present -> UpstreamType.NONE
        capabilities == null -> UpstreamType.UNKNOWN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> UpstreamType.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamType.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamType.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UpstreamType.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> UpstreamType.BLUETOOTH
        else -> UpstreamType.OTHER
    }
    override fun close() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
    }
}
