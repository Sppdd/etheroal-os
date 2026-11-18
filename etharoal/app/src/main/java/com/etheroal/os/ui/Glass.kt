package com.etheroal.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val rim = Brush.verticalGradient(listOf(Halo.RimTop, Halo.RimBottom))

/**
 * A glass surface: dark veil plus a bright rim. There is deliberately no live blur of what is
 * behind it; blur is the most expensive thing a weak GPU can be asked to do every frame.
 */
fun Modifier.glass(shape: Shape, tint: Color = Halo.Glass): Modifier =
    clip(shape).background(tint).border(1.dp, rim, shape)

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Box(modifier.glass(shape)) {
        if (accent != null) {
            // A wash of the accent colour from the top edge marks the panel (urgent, for example).
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent))))
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A pill button. [filled] is the one main action of its group. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = if (filled) Halo.Pearl else Color(0x14FFFFFF)
    val fg = if (filled) Halo.Ink else Halo.Pearl
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)                       // touch target
            .padding(vertical = 5.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
            .then(if (filled) Modifier else Modifier.border(1.dp, Halo.RimBottom, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.5f))
    }
}

/** A quiet status tag: a coloured dot and a few words. Not clickable. */
@Composable
fun Tag(text: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Halo.Mist)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Eyebrow,
        color = Halo.Mist,
        modifier = modifier.padding(top = 12.dp, start = 4.dp).semantics { heading() },
    )
}

/** A single-line text field set in a glass pill, with a small light on its left. */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Search,
    onSubmit: () -> Unit = {},
) {
    val clear = Color.Transparent
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Halo.Mist) },
        leadingIcon = {
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Halo.Periwinkle, Halo.Ember))),
            )
        },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onSubmit() }),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = clear, unfocusedContainerColor = clear, disabledContainerColor = clear,
            focusedIndicatorColor = clear, unfocusedIndicatorColor = clear, disabledIndicatorColor = clear,
        ),
        modifier = modifier.glass(CircleShape, Halo.GlassRaised),
    )
}
