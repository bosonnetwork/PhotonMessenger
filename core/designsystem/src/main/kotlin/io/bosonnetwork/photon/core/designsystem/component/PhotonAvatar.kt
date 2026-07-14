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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import kotlin.math.absoluteValue

/**
 * Circular user/contact/channel avatar (X-A2). Renders the image at [model] when present, falling
 * back to the person's initials on a per-identity tinted background (or a person/group icon when no
 * name is known). The fallback also covers image-load failures: the avatar endpoint legitimately
 * 404s for users who never set a photo, and a blank circle would otherwise remain.
 */
@Composable
fun PhotonAvatar(
    model: Any?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    name: String? = null,
    /** Seed for the fallback background tint, so different people get different colors. */
    colorKey: String? = null,
    isChannel: Boolean = false,
    contentDescription: String? = null,
) {
    val base = modifier.size(size).clip(CircleShape)
    if (model != null) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = base,
            loading = { FallbackAvatar(size, name, colorKey, isChannel, contentDescription) },
            error = { FallbackAvatar(size, name, colorKey, isChannel, contentDescription) },
        )
    } else {
        Box(modifier = base) {
            FallbackAvatar(size, name, colorKey, isChannel, contentDescription)
        }
    }
}

@Composable
private fun FallbackAvatar(
    size: Dp,
    name: String?,
    colorKey: String?,
    isChannel: Boolean,
    contentDescription: String?,
) {
    val background = avatarColorFor(colorKey ?: name)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        val initials = name?.let(::initialsOf)
        if (!initials.isNullOrBlank()) {
            Text(
                text = initials,
                style = when {
                    size >= 72.dp -> MaterialTheme.typography.headlineMedium
                    size >= 48.dp -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                },
                color = Color.White,
            )
        } else {
            Icon(
                if (isChannel) Icons.Outlined.Groups else Icons.Filled.Person,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.55f),
                tint = Color.White,
            )
        }
    }
}

/** Muted, white-text-safe hues; stable per identity so an avatar keeps its color everywhere. */
private val AvatarColors = listOf(
    Color(0xFFE56B6F), // coral
    Color(0xFFE88C30), // orange
    Color(0xFFC0578E), // magenta
    Color(0xFF2F9E6E), // green
    Color(0xFF3D8BD9), // blue
    Color(0xFF7C64D6), // violet
    Color(0xFF2FA3A8), // teal
)

/**
 * Stable per-identity tint, shared by avatar fallbacks and channel sender names so one person is
 * always the same color across the app.
 */
fun identityColor(key: String?): Color = avatarColorFor(key)

private fun avatarColorFor(key: String?): Color =
    if (key.isNullOrBlank()) AvatarColors[4]
    else AvatarColors[key.hashCode().absoluteValue % AvatarColors.size]

/** Up to two uppercase initials from a display name; blank if none can be derived. */
private fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
