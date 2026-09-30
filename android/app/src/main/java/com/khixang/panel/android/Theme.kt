package com.khixang.panel.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppleBlue = Color(0xFF007AFF)
val OnlineGreen = Color(0xFF30C759)
val MemoryTeal = Color(0xFF00B8CC)
val OfflineOrange = Color(0xFFFF9500)
val LossRed = Color(0xFFFF3B30)
val LocalFormEnabled = staticCompositionLocalOf { true }
@Composable fun MonitorTheme(mode: String = "system", accent: String = "#007AFF", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val tint = runCatching { Color(android.graphics.Color.parseColor(accent)) }.getOrDefault(AppleBlue)
    val colors = if(dark) darkColorScheme(primary = tint, background = Color.Black, surface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFF2C2C2E), onSurface = Color.White, onBackground = Color.White, onSurfaceVariant = Color(0xFF98989F), outlineVariant = Color(0xFF38383A))
    else lightColorScheme(primary = tint, background = Color(0xFFF2F2F7), surface = Color.White, surfaceVariant = Color(0xFFF2F2F7), onSurface = Color(0xFF111113), onBackground = Color(0xFF111113), onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0xFFE5E5EA))
    MaterialTheme(colorScheme = colors, typography = Typography(
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp), bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)), content = content)
}
@Composable fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun SectionTitle(text: String) { Text(t(text), style = MaterialTheme.typography.titleMedium) }
@Composable fun Hint(text: String) { Text(t(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun ErrorText(text: String?) { if(text != null) Text(text, color = OfflineOrange, style = MaterialTheme.typography.bodyMedium) }
@Composable fun Field(label: String, text: String, change: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value = text, onValueChange = change, enabled = LocalFormEnabled.current, modifier = modifier.fillMaxWidth(), label = { Text(t(label)) }, singleLine = !multiline,
        visualTransformation = if(secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false))
}
