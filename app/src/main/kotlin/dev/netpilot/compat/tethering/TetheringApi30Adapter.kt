package dev.netpilot.compat.tethering

import android.content.Context

class TetheringApi30Adapter(context: Context, onChange: () -> Unit) :
    ReflectiveTetheringAdapter(context, "Android 11 TetheringManager", true, false, onChange)
