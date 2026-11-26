package dev.netpilot.compat.tethering

import android.content.Context

class TetheringApi31Adapter(context: Context, onChange: () -> Unit) :
    ReflectiveTetheringAdapter(context, "Android 12–15 TetheringRequest", true, false, onChange)
