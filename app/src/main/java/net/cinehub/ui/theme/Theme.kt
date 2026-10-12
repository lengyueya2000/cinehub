package net.cinehub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val CineHubColors = darkColorScheme(
    primary = T.Play500,
    onPrimary = T.Text,
    secondary = T.Src500,
    onSecondary = T.Bg,
    background = T.Bg,
    onBackground = T.Text,
    surface = T.Surface,
    onSurface = T.Text,
    surfaceVariant = T.Surface2,
    onSurfaceVariant = T.TextDim,
    outline = T.BorderStrong,
    error = T.Err,
    onError = T.Bg,
)

private val CineHubType = Typography().let { base ->
    // 只覆盖我们实际用到的几档，其余沿用默认
    base.copy(
        titleLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = Fs.title,
            color = T.Text,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = Fs.body,
            color = T.Text,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = Fs.xxs,
            color = T.TextDim,
        ),
    )
}

@Composable
fun CineHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CineHubColors,
        typography = CineHubType,
        content = content,
    )
}
