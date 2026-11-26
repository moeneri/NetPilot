package dev.netpilot.i18n

import android.content.Context
import androidx.annotation.StringRes
import dev.netpilot.R
import dev.netpilot.core.model.ScheduleSlot
import java.util.Locale

class LocalizedStrings(val context: Context) {
    val locale: Locale get() = context.resources.configuration.locales[0]
    operator fun invoke(@StringRes id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    fun runtime(text: String): String = RuntimeText.translate(this, text)

    /** Translate shipped labels only; names entered by the user stay verbatim. */
    fun slotLabel(slot: ScheduleSlot): String = when {
        slot.id == "default-morning" && slot.label in setOf("上午", "Morning") -> invoke(R.string.slot_morning)
        slot.id == "default-afternoon" && slot.label in setOf("下午", "Afternoon") -> invoke(R.string.slot_afternoon)
        else -> slot.label
    }
}
