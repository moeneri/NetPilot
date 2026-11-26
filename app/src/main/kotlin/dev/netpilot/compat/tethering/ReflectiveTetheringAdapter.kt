package dev.netpilot.compat.tethering

import android.content.Context
import android.net.Network
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reflection is used in the root app_process, where hidden API access is separately detected. */
open class ReflectiveTetheringAdapter(
    context: Context,
    override val name: String,
    private val requestPreferred: Boolean,
    private val stopCallbackPreferred: Boolean,
    private val onChange: () -> Unit,
) : TetheringCompat {
    private val callbackExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "netpilot-tether-events").apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            initializationError = errorMessage(error)
            update { it.copy(eventsRegistered = false, observationError = initializationError) }
        }
    } }
    private val manager = runCatching { context.getSystemService("tethering") }.getOrNull()
    private val observation = AtomicReference(TetherObservation())
    private val activeRequests = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    private var eventCallback: Any? = null
    private var usbRegex = listOf<Regex>()
    private var wifiRegex = listOf<Regex>()
    var initializationError: String? = null; private set

    init {
        if (manager == null) { initializationError = "ROM 未提供 TetheringManager 服务"; observation.set(TetherObservation(observationError = initializationError)) }
        else runCatching {
            usbRegex = readRegex("getTetherableUsbRegexs")
            wifiRegex = readRegex("getTetherableWifiRegexs")
            val callbackType = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            require(callbackType.isInterface) { "ROM 回调类型不是标准接口" }
            eventCallback = proxy(callbackType) { method, args ->
                when (method) {
                    "onUpstreamChanged" -> update { it.copy(upstream = args?.getOrNull(0) as? Network, upstreamObserved = true, eventsRegistered = true, observationError = null) }
                    "onTetheredInterfacesChanged" -> update { it.copy(interfacesByType = classifyInterfaces(args?.getOrNull(0))) }
                    "onTetherableInterfaceRegexpsChanged" -> {
                        args?.getOrNull(0)?.let { regexObject ->
                            usbRegex = readRegexObject(regexObject, "getTetherableUsbRegexs")
                            wifiRegex = readRegexObject(regexObject, "getTetherableWifiRegexs")
                        }
                        refresh()
                    }
                    "onError" -> {
                        val iface = interfaceName(args?.getOrNull(0))
                        val code = args?.getOrNull(1) as? Int ?: 0
                        update { it.copy(errors = if (code == 0) it.errors - iface else it.errors + (iface to code)) }
                    }
                    "onClientsChanged" -> update { it.copy(clientAddressCounts = clients(args?.getOrNull(0))) }
                }
                null
            }
            manager!!.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, callbackType)
                .invoke(manager, callbackExecutor, eventCallback)
            refresh()
        }.onFailure { initializationError = errorMessage(it); observation.set(TetherObservation(observationError = initializationError)) }
    }

    fun configureRegex(usb: String, wifi: String) {
        if (usb.isNotBlank()) usbRegex = listOf(Regex(usb))
        if (wifi.isNotBlank()) wifiRegex = listOf(Regex(wifi))
        refresh()
    }

    override fun start(type: Int): TetherOperation {
        val service = manager ?: return TetherOperation(false, "API_UNAVAILABLE", initializationError ?: "共享服务不可用")
        if (!observation.get().eventsRegistered) return TetherOperation(false, "EVENTS_UNAVAILABLE", initializationError ?: "无法确认系统共享状态，未执行启动")
        val result = callbackOperation("StartTetheringCallback", "onTetheringStarted", "onTetheringFailed") { callbackType, callback ->
            val request = if (requestPreferred) buildRequest(type) else null
            val requestMethod = request?.let { value -> service.javaClass.methods.firstOrNull { it.name == "startTethering" && it.parameterCount == 3 && it.parameterTypes[0].isInstance(value) } }
            if (requestMethod != null) {
                activeRequests[type] = request!!
                requestMethod.invoke(service, request, callbackExecutor, callback)
            }
            else service.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType, Executor::class.java, callbackType).invoke(service, type, callbackExecutor, callback)
        }
        if (!result.accepted && !result.outcomeUncertain) activeRequests.remove(type)
        return result
    }

    override fun stop(type: Int): TetherOperation {
        val service = manager ?: return TetherOperation(false, "API_UNAVAILABLE", "共享服务不可用")
        // New request-specific stop must reuse the actual request; after process recovery use stop(type).
        val request = if (stopCallbackPreferred) activeRequests[type] else null
        val callbackType = runCatching { Class.forName("android.net.TetheringManager\$StopTetheringCallback") }.getOrNull()
        val method = if (request != null && callbackType != null) service.javaClass.methods.firstOrNull { it.name == "stopTethering" && it.parameterCount == 3 && it.parameterTypes[0].isInstance(request) } else null
        if (method != null && callbackType != null) {
            val result = callbackOperation("StopTetheringCallback", "onStopTetheringSucceeded", "onStopTetheringFailed") { _, callback -> method.invoke(service, request, callbackExecutor, callback) }
            if (result.accepted) activeRequests.remove(type)
            return result
        }
        return runCatching {
            service.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(service, type)
            activeRequests.remove(type)
            TetherOperation(true, detail = "已请求系统停止，等待共享接口回调")
        }.getOrElse { TetherOperation(false, errorCode(it), errorMessage(it)) }
    }

    private fun callbackOperation(type: String, successMethod: String, failureMethod: String, invoke: (Class<*>, Any) -> Unit): TetherOperation {
        val latch = CountDownLatch(1)
        val result = AtomicReference<TetherOperation>()
        return try {
            val callbackType = Class.forName("android.net.TetheringManager\$$type")
            val callback = proxy(callbackType) { method, args ->
                when (method) {
                    successMethod, "onTetheringStopped" -> { result.set(TetherOperation(true, detail = "系统已接受共享操作")); latch.countDown() }
                    failureMethod, "onStopTetheringFailed" -> {
                        val code = args?.getOrNull(0) as? Int ?: -1
                        result.set(TetherOperation(false, "TETHER_ERROR_$code", describeError(code))); latch.countDown()
                    }
                }
                null
            }
            invoke(callbackType, callback)
            if (latch.await(8, TimeUnit.SECONDS)) result.get() else TetherOperation(false, "CALLBACK_TIMEOUT", "系统未在 8 秒内返回结果；保留待确认操作以便恢复", outcomeUncertain = true)
        } catch (error: Exception) { TetherOperation(false, errorCode(error), errorMessage(error)) }
    }

    private fun buildRequest(type: Int): Any? = runCatching {
        val builderType = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderType.getConstructor(Int::class.javaPrimitiveType).newInstance(type)
        // Preserve the system's SSID, password, security, DHCP and entitlement settings.
        builderType.methods.firstOrNull { it.name == "setShouldShowEntitlementUi" && it.parameterCount == 1 }?.invoke(builder, false)
        builderType.getMethod("build").invoke(builder)
    }.getOrNull()

    override fun observation() = observation.get()
    override fun refresh() {
        val service = manager ?: return
        runCatching { service.javaClass.getMethod("getTetheredIfaces").invoke(service) }.getOrNull()?.let { interfaces ->
            val classified = classifyInterfaces(interfaces)
            // Preserve the precise typed callback mapping if deprecated string queries omit types.
            val previous = observation.get().interfacesByType
            if (classified != previous && (classified.values.any { it.isNotEmpty() } || asCollection(interfaces).isEmpty())) update { it.copy(interfacesByType = classified) }
        }
    }

    private fun classifyInterfaces(value: Any?): Map<Int, List<String>> {
        val result = mutableMapOf<Int, MutableList<String>>()
        asCollection(value).forEach { item ->
            val name = interfaceName(item)
            if (!name.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) return@forEach
            val type = if (item !is String) runCatching { item?.javaClass?.getMethod("getType")?.invoke(item) as? Int }.getOrNull() else null
            val resolved = type ?: when { usbRegex.any { it.matches(name) } -> TYPE_USB; wifiRegex.any { it.matches(name) } -> TYPE_WIFI; else -> -1 }
            if (resolved >= 0) result.getOrPut(resolved) { mutableListOf() }.add(name)
        }
        return result.mapValues { it.value.distinct() }
    }

    private fun clients(value: Any?): Map<Int, Int> {
        val counts = mutableMapOf(TYPE_USB to 0, TYPE_WIFI to 0)
        asCollection(value).forEach { client ->
            runCatching {
                client ?: return@runCatching
                val type = client.javaClass.getMethod("getTetheringType").invoke(client) as Int
                val addresses = asCollection(client.javaClass.getMethod("getAddresses").invoke(client))
                counts[type] = counts.getOrDefault(type, 0) + addresses.size
            }
        }
        return counts
    }

    private fun readRegex(method: String): List<Regex> = manager?.let { readRegexObject(it, method) } ?: emptyList()
    private fun readRegexObject(value: Any, method: String): List<Regex> = runCatching { asCollection(value.javaClass.getMethod(method).invoke(value)).mapNotNull { runCatching { Regex(it.toString()) }.getOrNull() } }.getOrDefault(emptyList())
    private fun interfaceName(item: Any?): String = if (item is String) item else runCatching { item?.javaClass?.getMethod("getInterface")?.invoke(item) as? String }.getOrNull().orEmpty()
    private fun asCollection(value: Any?): Collection<*> = when (value) { is Collection<*> -> value; is Array<*> -> value.toList(); else -> emptyList<Any>() }
    private fun update(transform: (TetherObservation) -> TetherObservation) { observation.updateAndGet(transform); onChange() }

    override fun close() {
        runCatching { eventCallback?.let { callback -> manager?.javaClass?.methods?.firstOrNull { it.name == "unregisterTetheringEventCallback" && it.parameterCount == 1 }?.invoke(manager, callback) } }
        callbackExecutor.shutdownNow()
    }

    companion object {
        private fun proxy(type: Class<*>, action: (String, Array<out Any?>?) -> Any?): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { obj, method, args ->
            when (method.name) { "hashCode" -> System.identityHashCode(obj); "equals" -> obj === args?.getOrNull(0); "toString" -> "NetPilot.${type.simpleName}"; else -> action(method.name, args) }
        }
        private fun unwrap(error: Throwable): Throwable = if (error is InvocationTargetException) error.targetException else error
        private fun errorCode(error: Throwable) = when (unwrap(error)) { is SecurityException -> "PERMISSION_DENIED"; is NoSuchMethodException, is ClassNotFoundException -> "API_UNAVAILABLE"; else -> "SYSTEM_SERVICE_ERROR" }
        private fun errorMessage(error: Throwable) = unwrap(error).let { "${it.javaClass.simpleName}: ${it.message ?: "系统服务调用失败"}" }
        private fun describeError(code: Int): String = when (code) {
            1 -> "未知共享接口"; 2 -> "共享服务不可用"; 3 -> "系统禁止此类网络共享"; 4 -> "网络接口不可用";
            5 -> "共享服务内部错误"; 6 -> "共享接口启动失败"; 7 -> "共享接口停止失败"; 8 -> "系统网络转发开启失败";
            9 -> "系统网络转发关闭失败"; 10 -> "共享接口配置失败"; 11 -> "运营商共享授权检查失败"; 12 -> "DHCP 服务启动失败";
            13 -> "运营商共享授权状态未知"; 14 -> "缺少更改系统共享权限"; 15 -> "缺少读取系统共享状态权限";
            16 -> "系统不识别此共享类型"; 17 -> "系统不识别此共享请求"; 18 -> "已有相同的共享请求";
            20 -> "等待系统 Soft AP 状态回调";
            else -> "系统共享错误 $code；查看 ROM 诊断日志"
        }
    }
}
