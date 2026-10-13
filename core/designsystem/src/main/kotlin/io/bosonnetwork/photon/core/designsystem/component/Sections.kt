/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.photon.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bosonnetwork.photon.core.designsystem.theme.brand

/*
 * Card-style pages, as in Boson Identity: titled sections, each a card on the page ground, stacked in
 * one scrolling column.
 */

/** A card on the page ground, in the brand's card color. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.brand.card,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/**
 * A titled group of a page: the title, optionally something at its end, and a card with the content.
 * Pages stack sections 24 dp apart; the title sits 8 dp above its card, so each title reads as
 * belonging to the card under it.
 */
@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                trailing?.invoke()
            }
        }
        SectionCard(content = content)
    }
}

/** The space between a page's sections. */
val SectionSpacing = 24.dp

/** The colors of a [ListItem] inside a [SectionCard]: on the card, not the page ground. */
@Composable
fun cardItemColors(): ListItemColors = ListItemDefaults.colors(containerColor = MaterialTheme.brand.card)

/**
 * One row of a card: an icon, a title and an optional explanation, and something at the end. With
 * [onClick] the whole row is the button. [tint] colors the icon and title (red for what destroys).
 */
@Composable
fun ActionRow(
    icon: ImageVector?,
    title: String,
    detail: String? = null,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        colors = cardItemColors(),
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint) } },
        headlineContent = { Text(title, color = tint) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, enabled = enabled, onClick = onClick) else Modifier,
    )
}

/** A page's column: scrolls, 16 dp from the edges, at most 640 dp wide and centered on wide screens. */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            content = content,
        )
    }
}
