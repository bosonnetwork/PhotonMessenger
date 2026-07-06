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

package io.photonmessenger.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/*
 * Brand palette (design spec section 5.1) expanded to the full Material 3 tonal roles, so derived
 * container and surface-container colors stay on-brand instead of falling back to the baseline
 * purple-tinted defaults. Light is built around the electric blue on cool near-white neutrals;
 * dark keeps the deep-obsidian background with blue-tinted elevated surfaces.
 */

// --- Light scheme ---
internal val LightPrimary = Color(0xFF2F6BE4)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFDBE6FF)
internal val LightOnPrimaryContainer = Color(0xFF0A2C6B)
internal val LightSecondary = Color(0xFF55617F)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFDDE4F9)
internal val LightOnSecondaryContainer = Color(0xFF121D38)
internal val LightTertiary = Color(0xFF0B8A62)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFBFF2DC)
internal val LightOnTertiaryContainer = Color(0xFF00281B)
internal val LightBackground = Color(0xFFF8FAFD)
internal val LightOnBackground = Color(0xFF181C24)
internal val LightSurface = Color(0xFFF8FAFD)
internal val LightOnSurface = Color(0xFF181C24)
internal val LightSurfaceVariant = Color(0xFFE1E6F0)
internal val LightOnSurfaceVariant = Color(0xFF444A57)
internal val LightOutline = Color(0xFF747B89)
internal val LightOutlineVariant = Color(0xFFC4CAD6)
internal val LightInverseSurface = Color(0xFF2D3139)
internal val LightInverseOnSurface = Color(0xFFEFF1F8)
internal val LightInversePrimary = Color(0xFFAEC6FF)
internal val LightSurfaceDim = Color(0xFFD8DAE2)
internal val LightSurfaceBright = Color(0xFFF8FAFD)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF2F4FA)
internal val LightSurfaceContainer = Color(0xFFECEFF6)
internal val LightSurfaceContainerHigh = Color(0xFFE6E9F1)
internal val LightSurfaceContainerHighest = Color(0xFFE0E3EB)

// --- Dark scheme ---
internal val DarkPrimary = Color(0xFFAEC6FF)
internal val DarkOnPrimary = Color(0xFF082A66)
internal val DarkPrimaryContainer = Color(0xFF23509E)
internal val DarkOnPrimaryContainer = Color(0xFFDBE6FF)
internal val DarkSecondary = Color(0xFFBDC7E6)
internal val DarkOnSecondary = Color(0xFF27324E)
internal val DarkSecondaryContainer = Color(0xFF3D4966)
internal val DarkOnSecondaryContainer = Color(0xFFDDE4F9)
internal val DarkTertiary = Color(0xFF52DCA9)
internal val DarkOnTertiary = Color(0xFF003827)
internal val DarkTertiaryContainer = Color(0xFF00674A)
internal val DarkOnTertiaryContainer = Color(0xFFBFF2DC)
internal val DarkBackground = Color(0xFF0B0F19)
internal val DarkOnBackground = Color(0xFFE2E4EC)
internal val DarkSurface = Color(0xFF0B0F19)
internal val DarkOnSurface = Color(0xFFE2E4EC)
internal val DarkSurfaceVariant = Color(0xFF2A303F)
internal val DarkOnSurfaceVariant = Color(0xFFC4CAD6)
internal val DarkOutline = Color(0xFF8E94A2)
internal val DarkOutlineVariant = Color(0xFF444A57)
internal val DarkInverseSurface = Color(0xFFE2E4EC)
internal val DarkInverseOnSurface = Color(0xFF2D3139)
internal val DarkInversePrimary = Color(0xFF2F6BE4)
internal val DarkSurfaceDim = Color(0xFF0B0F19)
internal val DarkSurfaceBright = Color(0xFF313644)
internal val DarkSurfaceContainerLowest = Color(0xFF060912)
internal val DarkSurfaceContainerLow = Color(0xFF131823)
internal val DarkSurfaceContainer = Color(0xFF171C28)
internal val DarkSurfaceContainerHigh = Color(0xFF212633)
internal val DarkSurfaceContainerHighest = Color(0xFF2C313E)
