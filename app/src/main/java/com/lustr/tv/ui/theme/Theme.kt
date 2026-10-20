package com.lustr.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.lustr.tv.R

/** lustr's default dark theme (static/themes/default.css in the server). */
object LustrColors {
    val surfaceBase = Color(0xFF111827)      // page background
    val surfaceRaised = Color(0xFF1F2937)    // sidebar, panels, cards
    val surfaceElevated = Color(0xFF374151)  // buttons at rest
    val border = Color(0xFF374151)
    val textBrightest = Color(0xFFF3F4F6)
    val textBrighter = Color(0xFFE5E7EB)     // headings
    val textPrimary = Color(0xFFD1D5DB)
    val textSecondary = Color(0xFF9CA3AF)
    val primaryBase = Color(0xFF2563EB)      // focus / main action
    val primaryLight = Color(0xFF3B82F6)     // progress bars, tab underline
    val primarySoft = Color(0xFF60A5FA)      // the logo, accents
    val activeBackground = Color(0xFF1E3A8A) // the open library in the sidebar
    val activeText = Color(0xFFDBEAFE)
    val danger = Color(0xFFF87171)
    val favorite = Color(0xFFEAB308)
}

/** The web app's logo font: Tangerine Bold (SIL Open Font License, see third_party/). */
val Tangerine = FontFamily(Font(R.font.tangerine_bold, FontWeight.Bold))

private val colors = darkColorScheme(
    primary = LustrColors.primaryLight,
    onPrimary = Color.White,
    background = LustrColors.surfaceBase,
    onBackground = LustrColors.textBrightest,
    surface = LustrColors.surfaceRaised,
    onSurface = LustrColors.textBrightest,
    surfaceVariant = LustrColors.surfaceElevated,
    onSurfaceVariant = LustrColors.textSecondary,
    border = LustrColors.border,
    error = LustrColors.danger,
)

@Composable
fun LustrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

/** "lustr" as on the web app's header and login page. */
@Composable
fun LustrLogo(modifier: Modifier = Modifier, size: TextUnit = 44.sp) {
    Text(
        "lustr",
        modifier = modifier,
        color = LustrColors.primarySoft,
        fontFamily = Tangerine,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        lineHeight = size,
    )
}
