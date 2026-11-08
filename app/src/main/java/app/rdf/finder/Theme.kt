package app.rdf.finder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Colours that Material's colour scheme has no slot for: status LEDs and readout accent. */
class TacPalette(
    val ok: Color,
    val bad: Color,
    val warn: Color,
    val neutral: Color,
    val accent: Color,
    val muted: Color,
    val friendly: Color,
    val hostile: Color,
    val night: Boolean,
)

private val DayPalette = TacPalette(
    ok = Color(0xFF8BC34A),
    bad = Color(0xFFE5483A),
    warn = Color(0xFFFFB300),
    neutral = Color(0xFF7C8470),
    accent = Color(0xFFFFB300),
    muted = Color(0xFF8A917A),
    friendly = Color(0xFF3FA7FF),
    hostile = Color(0xFFE5483A),
    night = false,
)

/** Red-on-black: keeps the user's dark adaptation in the field at night. */
private val NightPalette = TacPalette(
    ok = Color(0xFFCC3322),
    bad = Color(0xFFFF4433),
    warn = Color(0xFFFF6644),
    neutral = Color(0xFF7A2A22),
    accent = Color(0xFFFF4433),
    muted = Color(0xFF9A3A30),
    friendly = Color(0xFFFF6644),
    hostile = Color(0xFFFF2211),
    night = true,
)

private val DayScheme = darkColorScheme(
    background = Color(0xFF0A0C09),
    surface = Color(0xFF0A0C09),
    surfaceContainer = Color(0xFF14180F),
    surfaceContainerHigh = Color(0xFF1B2015),
    surfaceVariant = Color(0xFF1B2015),
    onBackground = Color(0xFFD7DCC8),
    onSurface = Color(0xFFD7DCC8),
    onSurfaceVariant = Color(0xFF8A917A),
    primary = Color(0xFF8DA352),
    onPrimary = Color(0xFF0A0C09),
    primaryContainer = Color(0xFF2E3A1B),
    onPrimaryContainer = Color(0xFFD7DCC8),
    secondary = Color(0xFFFFB300),
    outline = Color(0xFF3A4230),
    outlineVariant = Color(0xFF262C1D),
    error = Color(0xFFE5483A),
)

private val NightScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainer = Color(0xFF120403),
    surfaceContainerHigh = Color(0xFF1A0605),
    surfaceVariant = Color(0xFF1A0605),
    onBackground = Color(0xFFFF5544),
    onSurface = Color(0xFFFF5544),
    onSurfaceVariant = Color(0xFF9A3A30),
    primary = Color(0xFFB02A1E),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A0E0A),
    onPrimaryContainer = Color(0xFFFF5544),
    secondary = Color(0xFFFF4433),
    outline = Color(0xFF55201A),
    outlineVariant = Color(0xFF330F0B),
    error = Color(0xFFFF4433),
)

val LocalTac = staticCompositionLocalOf { DayPalette }

val OK: Color @Composable get() = LocalTac.current.ok
val BAD: Color @Composable get() = LocalTac.current.bad
val WARN: Color @Composable get() = LocalTac.current.warn
val NEUTRAL: Color @Composable get() = LocalTac.current.neutral
val ACCENT: Color @Composable get() = LocalTac.current.accent
val MUTED: Color @Composable get() = LocalTac.current.muted

private val Square = RoundedCornerShape(2.dp)

private fun monoTypography(): Typography {
    val t = Typography()
    fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace)
    return Typography(
        displayLarge = t.displayLarge.mono(), displayMedium = t.displayMedium.mono(), displaySmall = t.displaySmall.mono(),
        headlineLarge = t.headlineLarge.mono(), headlineMedium = t.headlineMedium.mono(), headlineSmall = t.headlineSmall.mono(),
        titleLarge = t.titleLarge.mono(), titleMedium = t.titleMedium.mono(), titleSmall = t.titleSmall.mono(),
        bodyLarge = t.bodyLarge.mono(), bodyMedium = t.bodyMedium.mono(), bodySmall = t.bodySmall.mono(),
        labelLarge = t.labelLarge.mono(), labelMedium = t.labelMedium.mono(), labelSmall = t.labelSmall.mono(),
    )
}

@Composable
fun TacticalTheme(night: Boolean, content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (night) NightScheme else DayScheme
    CompositionLocalProvider(
        LocalTac provides if (night) NightPalette else DayPalette,
        LocalMinimumInteractiveComponentSize provides 0.dp, // compact: no 48 dp minimum around switches, radios, text buttons
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(Square, Square, Square, Square, Square),
            typography = monoTypography(),
            content = content,
        )
    }
}

private val ButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** Square, tall, glove-friendly filled button. */
@Composable
fun TacButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 40.dp),
    enabled = enabled,
    shape = Square,
    contentPadding = ButtonPadding,
    colors = ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContentColor = MaterialTheme.colorScheme.outline,
    ),
    content = content,
)

/** Square outlined button for secondary actions. */
@Composable
fun TacOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = 40.dp),
    enabled = enabled,
    shape = Square,
    contentPadding = ButtonPadding,
    border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

/** Flat bordered panel used instead of Material's rounded, elevated card. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = Square,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
