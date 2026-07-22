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

package io.bosonnetwork.photon.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.model.UiDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status-dot size and its gap to the title, shared so the detail lines can indent to match. */
internal val DeviceRowLeadingSize = 14.dp
internal val DeviceRowLeadingGap = 8.dp

/**
 * Fixed trailing-slot width so a text action (e.g. "Revoke") and an icon action share the same
 * horizontal center across rows, however different their intrinsic widths are.
 */
internal val DeviceRowTrailingWidth = 88.dp

/**
 * One device/session row shared by the Sessions and Devices screens. Unlike Material's [androidx.compose
 * .material3.ListItem] (which top-aligns the leading/trailing once the body grows past two lines), this
 * row centers the [trailing] action on the whole block vertically, while an optional [leading] marker
 * centers on the title line. The body is the device [title] plus [DeviceDetails].
 */
@Composable
internal fun DeviceListRow(
    title: String,
    device: UiDevice,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(DeviceRowLeadingGap))
                }
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(2.dp))
            DeviceDetails(
                device = device,
                // Indent the details to line up under the title, past the leading marker when present.
                modifier = if (leading != null) {
                    Modifier.padding(start = DeviceRowLeadingSize + DeviceRowLeadingGap)
                } else {
                    Modifier
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/**
 * The body of a device/session row: the app name, the compact last-active line, and the abbreviated
 * device id. The device name is the row title and is rendered by [DeviceListRow].
 */
@Composable
internal fun DeviceDetails(device: UiDevice, modifier: Modifier = Modifier) {
    Column(modifier) {
        device.app?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            device.activityLine(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            shortId(device.deviceId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The compact last-active line: "<last active time> - <last active address>", kept prefix-free so it
 * stays on one line. Online/offline state is conveyed by the status icon, not this text. Falls back to
 * a neutral label when neither a time nor an address is available.
 */
@Composable
internal fun UiDevice.activityLine(): String {
    val time = lastActive.takeIf { it > 0 }?.let { formatDeviceTimestamp(it) }
    val address = lastAddress?.takeIf { it.isNotBlank() }
    return listOfNotNull(time, address).joinToString(" - ")
        .ifEmpty { stringResource(R.string.settings_device_no_recent_activity) }
}

// ISO 8601 date-time with a space instead of 'T' (e.g. 2026-07-17 14:03:22). The API returns UTC epoch
// millis; SimpleDateFormat renders it in the device's local time zone (the default), converting for us.
private fun formatDeviceTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
