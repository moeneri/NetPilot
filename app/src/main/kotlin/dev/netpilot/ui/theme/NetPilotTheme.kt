package dev.netpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.netpilot.core.model.ThemeMode

val Forest = Color(0xFF235A46)
val Lime = Color(0xFFC3E5A3)
val StatusGreen = Color(0xFF39714D)
val StatusBlue = Color(0xFF3B6C98)
val StatusAmber = Color(0xFF94702A)
val StatusRed = Color(0xFFB64F4F)

private val Light = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Color(0xFFE6F0E6), onPrimaryContainer = Color(0xFF244D36),
    secondary = Color(0xFF60715F), onSecondary = Color.White, secondaryContainer = Color(0xFFEDF1E9),
    background = Color(0xFFF5F7F4), onBackground = Color(0xFF1C2C24),
    surface = Color(0xFFFEFFFC), onSurface = Color(0xFF1C2C24), surfaceVariant = Color(0xFFF0F3ED),
    onSurfaceVariant = Color(0xFF758076), outline = Color(0xFF9DAA9E), outlineVariant = Color(0xFFE0E6DC),
    error = StatusRed, errorContainer = Color(0xFFF8EAE8), onErrorContainer = Color(0xFF863D39),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA4D2B5), onPrimary = Color(0xFF153A2B), primaryContainer = Color(0xFF2B4935), onPrimaryContainer = Color(0xFFCCEDD5),
    secondary = Color(0xFFBAC7B3), secondaryContainer = Color(0xFF303F32),
    background = Color(0xFF111A15), onBackground = Color(0xFFE2ECE2),
    surface = Color(0xFF1B261E), onSurface = Color(0xFFE2ECE2), surfaceVariant = Color(0xFF243128),
    onSurfaceVariant = Color(0xFFA8B6A9), outline = Color(0xFF667869), outlineVariant = Color(0xFF314235),
    error = Color(0xFFEEB1AB), errorContainer = Color(0xFF5D3432), onErrorContainer = Color(0xFFFADAD6),
)

private val NetPilotTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp),
)

@Composable fun NetPilotTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = NetPilotTypography, content = content)
}
