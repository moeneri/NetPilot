package dev.netpilot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.netpilot.compat.usb.USB_STATE_ACTION

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == USB_STATE_ACTION) dispatchRecovery(context, "WAKE") }
}
