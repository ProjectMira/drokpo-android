package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.monospacedDigit

/**
 * `TextField(…).textFieldStyle(.roundedBorder)` (phone sign-in): a field on
 * the plain background with a thin rounded outline and placeholder text.
 * [textAlign] / [textStyle] cover the centred OTP field
 * (`typography.title2.monospacedDigit()`).
 */
@Composable
fun RoundedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = DrokpoTheme.typography.body,
    textAlign: TextAlign = TextAlign.Start,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = DrokpoTheme.colors
    val style = textStyle.copy(
        color = if (enabled) colors.label else colors.tertiaryLabel,
        textAlign = textAlign,
    )
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled,
        textStyle = style,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(if (colors.isDark) colors.secondaryBackground else colors.background, shape)
                    .border(0.5.dp, colors.opaqueSeparator, shape)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, style = style, color = colors.placeholderText, modifier = Modifier.fillMaxWidth())
                }
                inner()
            }
        },
    )
}

@DrokpoPreviews
@Composable
private fun TextFieldsPreview() {
    DrokpoTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundedTextField(
                value = "",
                onValueChange = {},
                placeholder = "Phone number",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            RoundedTextField(
                value = "",
                onValueChange = {},
                placeholder = "123456",
                textStyle = DrokpoTheme.typography.title2.monospacedDigit(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
