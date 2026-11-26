package dev.netpilot.compat.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.core.content.ContextCompat
import java.lang.reflect.Proxy

data class ReceiverRegistration(val sticky: Intent?, val close: () -> Unit)

/** app_process may have no AMS application record. Use an unattached Binder receiver if needed. */
object RootBroadcastCompat {
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter, exported: Boolean): ReceiverRegistration {
        return try {
            val sticky = ContextCompat.registerReceiver(context, receiver, filter, if (exported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED)
            ReceiverRegistration(sticky) { runCatching { context.unregisterReceiver(receiver) } }
        } catch (publicError: Exception) {
            if (Process.myUid() != 0) throw publicError
            registerUnattached(context, receiver, filter, exported)
        }
    }

    private fun registerUnattached(context: Context, receiver: BroadcastReceiver, filter: IntentFilter, exported: Boolean): ReceiverRegistration {
        val activityManager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        val receiverType = Class.forName("android.content.IIntentReceiver")
        val stub = Class.forName("android.content.IIntentReceiver\$Stub")
        // Discover the AIDL operation through the generated public metadata. Private
        // TRANSACTION_* fields are restricted when the app targets newer Android releases.
        val transactionName = stub.getMethod("getDefaultTransactionName", Int::class.javaPrimitiveType)
        val transaction = (IBinder.FIRST_CALL_TRANSACTION until IBinder.FIRST_CALL_TRANSACTION + 32)
            .firstOrNull { transactionName.invoke(null, it) == "performReceive" }
            ?: error("ROM 未提供可识别的广播接收 Binder 操作")
        val receiveMethod = receiverType.methods.first { it.name == "performReceive" }
        require(receiveMethod.parameterTypes.all { it in setOf(Intent::class.java, Bundle::class.java, String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType) }) { "Unsupported framework receiver signature" }
        val descriptor = "android.content.IIntentReceiver"
        val handler = Handler(Looper.getMainLooper())
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
                if (code != transaction) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(descriptor)
                val arguments = receiveMethod.parameterTypes.map { type -> when {
                    type == Intent::class.java -> if (data.readInt() == 0) null else Intent.CREATOR.createFromParcel(data)
                    type == Bundle::class.java -> if (data.readInt() == 0) null else Bundle.CREATOR.createFromParcel(data)
                    type == String::class.java -> data.readString()
                    type == Int::class.javaPrimitiveType -> data.readInt()
                    type == Boolean::class.javaPrimitiveType -> data.readInt() != 0
                    else -> throw IllegalStateException("Unsupported framework receiver parameter: ${type.name}")
                } }
                val intent = arguments.firstOrNull { it is Intent } as? Intent ?: return true
                handler.post {
                    try { receiver.onReceive(context, intent) }
                    finally {
                        if (arguments.getOrNull(4) == true) runCatching {
                            val finish = activityManager.javaClass.methods.first { it.name == "finishReceiver" }.apply { isAccessible = true }
                            var integers = 0
                            val finishArgs = finish.parameterTypes.map { type -> when {
                                type == IBinder::class.java -> this
                                type == Int::class.javaPrimitiveType -> if (integers++ == 0) arguments.getOrNull(1) as? Int ?: 0 else intent.flags
                                type == String::class.java -> arguments.getOrNull(2) as? String
                                type == Bundle::class.java -> arguments.getOrNull(3) as? Bundle
                                type == Boolean::class.javaPrimitiveType -> false
                                else -> null
                            } }.toTypedArray()
                            finish.invoke(activityManager, *finishArgs)
                        }
                    }
                }
                return true
            }
        }
        val callback = Proxy.newProxyInstance(receiverType.classLoader, arrayOf(receiverType)) { proxy, method, args -> when (method.name) {
            "asBinder" -> binder; "hashCode" -> System.identityHashCode(proxy); "equals" -> proxy === args?.getOrNull(0); "toString" -> "NetPilotRootReceiver"; else -> null
        } }
        binder.attachInterface(callback as IInterface, descriptor)
        val method = activityManager.javaClass.methods.firstOrNull { it.name == "registerReceiverWithFeature" && it.parameterTypes.contains(receiverType) }
            ?: activityManager.javaClass.methods.first { it.name == "registerReceiver" && it.parameterTypes.contains(receiverType) }
        method.isAccessible = true
        var strings = 0
        var integers = 0
        var passedFilter = false
        val arguments = method.parameterTypes.map { type -> when {
            type == receiverType -> callback
            type == IntentFilter::class.java -> { passedFilter = true; filter }
            type == String::class.java -> if (passedFilter) "android.permission.NETWORK_STACK" else if (strings++ == 0) context.packageName else null
            type == Int::class.javaPrimitiveType -> if (integers++ == 0) 0 else if (Build.VERSION.SDK_INT >= 33) if (exported) 2 else 4 else 0
            type == Boolean::class.javaPrimitiveType -> false
            type.name == "android.app.IApplicationThread" -> null
            else -> throw IllegalStateException("Unsupported framework registration parameter: ${type.name}")
        } }.toTypedArray()
        val sticky = method.invoke(activityManager, *arguments) as? Intent
        return ReceiverRegistration(sticky) {
            runCatching { activityManager.javaClass.methods.first { it.name == "unregisterReceiver" }.apply { isAccessible = true }.invoke(activityManager, callback) }
        }
    }
}
