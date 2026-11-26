package dev.netpilot.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage { SYSTEM, CHINESE, ENGLISH }

/** Local UI preference: changing language never requires a Root connection. */
object AppLocale {
    private const val PREFERENCES = "appearance"
    private const val LANGUAGE = "language"

    fun read(context: Context): AppLanguage {
        val value = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE, null)
        return AppLanguage.entries.firstOrNull { it.name == value } ?: AppLanguage.SYSTEM
    }

    fun save(context: Context, language: AppLanguage): Boolean = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(LANGUAGE, language.name).commit()

    fun resolve(language: AppLanguage, systemLocales: LocaleList): Locale = when (language) {
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.SYSTEM -> if (!systemLocales.isEmpty && systemLocales[0].language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    }

    fun context(context: Context, language: AppLanguage, configuration: Configuration = context.resources.configuration): Context {
        val locale = resolve(language, configuration.locales)
        val override = Configuration(configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        // Keep the Activity in the ContextWrapper chain so dialogs, keyboards,
        // and window services retain their Activity token after localization.
        return ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(override) }
    }

    fun strings(context: Context, language: AppLanguage = read(context)) = LocalizedStrings(context(context, language))
}

@Composable
fun ProvideAppLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(base, configuration, language) { AppLocale.context(base, language, configuration) }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}

@Composable
fun rememberLocalizedStrings(language: AppLanguage? = null): LocalizedStrings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, language) {
        LocalizedStrings(if (language == null) context else AppLocale.context(context, language, configuration))
    }
}
