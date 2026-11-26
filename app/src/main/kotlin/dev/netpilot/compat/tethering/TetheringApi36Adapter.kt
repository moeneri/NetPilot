package dev.netpilot.compat.tethering

import android.content.Context

class TetheringApi36Adapter(context: Context, onChange: () -> Unit) :
    ReflectiveTetheringAdapter(context, "Android 16+ 能力适配", true, true, onChange)
