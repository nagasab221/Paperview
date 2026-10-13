package com.paperview.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperview.mobile.ui.theme.PaperTheme

/** The single hairline used everywhere a boundary is needed. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Int = 0) {
    HorizontalDivider(
        modifier = modifier.padding(start = inset.dp),
        thickness = 1.dp,
        color = PaperTheme.colors.hairline,
    )
}

/**
 * A section label: small, letterspaced, uppercase, over a rule. This is the
 * app's main organising device in place of cards.
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = PaperTheme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        Hairline()
    }
}

/**
 * A tappable row: line icon, title, description, chevron. The workhorse of the
 * Tools and Settings screens.
 */
@Composable
fun PaperRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = PaperTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (enabled) colors.ink else colors.muted,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.ink else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (showChevron && onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.muted,
            )
        }
    }
}

/** Primary action. Flat, square-ish, full width — a printed button, not a pill. */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = PaperTheme.colors.accent,
            contentColor = PaperTheme.colors.onAccent,
            disabledContainerColor = PaperTheme.colors.hairline,
            disabledContentColor = PaperTheme.colors.muted,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.Unspecified)
    }
}

/** Secondary action: outlined in hairline, ink text. */
@Composable
fun PaperOutlinedButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, PaperTheme.colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PaperTheme.colors.ink),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** A chip-style toggle drawn with rules rather than fills. */
@Composable
fun PaperChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PaperTheme.colors
    Box(
        modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .background(
                if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, colors.hairline, MaterialTheme.shapes.small),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onAccent else colors.ink,
        )
    }
}

/** Text field styled down to a hairline box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    isError: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val colors = PaperTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        isError = isError,
        shape = MaterialTheme.shapes.medium,
        label = label?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        placeholder = placeholder?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.muted) }
        },
        leadingIcon = leadingIcon?.let {
            { Icon(it, null, Modifier.size(18.dp), tint = colors.muted) }
        },
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.hairline,
            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = colors.accent,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
        ),
    )
}

/** Screen title bar: serif title, no elevation, hairline underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val colors = PaperTheme.colors
    Column(modifier) {
        TopAppBar(
            title = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                onBack?.let {
                    IconButton(onClick = it) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = colors.ink,
                        )
                    }
                }
            },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background,
                titleContentColor = colors.ink,
                actionIconContentColor = colors.ink,
            ),
        )
        Hairline()
    }
}

/** Large editorial page heading, used at the top of a scrolling screen. */
@Composable
fun PaperMasthead(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
        actions?.invoke()
    }
}

/** Quiet empty state — a rule, a line of serif, a line of explanation. */
@Composable
fun PaperEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = PaperTheme.colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        action?.let { (label, act) ->
            Spacer(Modifier.height(18.dp))
            PaperOutlinedButton(label, onClick = act)
        }
    }
}

/** Standard horizontal page margin. */
val PageMargin = PaddingValues(horizontal = 20.dp)

@Composable
fun InlineTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) PaperTheme.colors.accent else PaperTheme.colors.muted,
        )
    }
}
