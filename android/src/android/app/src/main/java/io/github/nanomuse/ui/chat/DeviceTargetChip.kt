package io.github.nanomuse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatViewModel
import io.github.nanomuse.hub.Hub
import io.github.nanomuse.ui.home.MuseTones

/**
 * Above the composer while the chat is connected to a device (#907): "To Desk" with a dot for
 * whether it is online, a menu to switch to another device or back to this phone, and × to
 * send here again. Nothing while the chat sends to this phone.
 */
@Composable
fun DeviceTargetHost(viewModel: ChatViewModel) {
    val bridge = viewModel.nmRemoteBridge
    val target by bridge.target.collectAsState()
    val current = target ?: return
    val context = LocalContext.current
    val all by Hub.devices.collectAsState()
    val me = remember { Hub.deviceId(context) }
    val others = all.filter { it.id != me && it.kind != "web" && it.name.isNotBlank() }
    val device = others.firstOrNull { it.id == current.id }
    val name = device?.name ?: current.name
    var menu by remember { mutableStateOf(false) }
    val describe = stringResource(R.string.nm_device_target_describe, name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MuseTones.fill, RoundedCornerShape(14.dp))
            .padding(start = 12.dp),
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { menu = true }
                    .semantics { contentDescription = describe }
                    .padding(vertical = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Icon(if (device?.kind == "phone") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.nm_device_target_to, name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).background(if (device?.online == true) Color(0xFF34C759) else Color.Gray.copy(alpha = 0.5f), CircleShape))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (d in others) {
                    DropdownMenuItem(
                        text = { Text(d.name) },
                        leadingIcon = { Icon(if (d.kind == "phone") Icons.Outlined.PhoneAndroid else Icons.Outlined.Computer, contentDescription = null) },
                        onClick = { menu = false; bridge.connect(d) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.nm_device_target_this_phone)) },
                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                    onClick = { menu = false; bridge.disconnect() },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { bridge.disconnect() }) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.nm_device_target_back), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
