package dev.netpilot

import android.app.Application
import dev.netpilot.data.repository.NetPilotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NetPilotApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { NetPilotRepository(this) }
}
