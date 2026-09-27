package com.example.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The navigation app chooser: a card over a transparent background, drawn in a single window (no
 * separate dialog window). Tapping outside the card closes it. Shown by [ChooserOverlay] and
 * [ChooserActivity].
 */
@Composable
fun ChooserContent(
    url: String,
    title: String,
    settings: AutoOpenSettings,
    launcher: NavigatorLauncher,
    onClose: () -> Unit,
) {
    val preferredApp = settings.preferredApp
    var timeLeft by remember { mutableIntStateOf(settings.delaySeconds) }
    var isAutoOpenEnabled by remember { mutableStateOf(preferredApp != ReceiverRepository.APP_NONE && settings.delaySeconds > 0) }
    val scope = rememberCoroutineScope()

    // Not inside an `if (timeLeft > 0)`: when the countdown reached 0 that condition would remove
    // this effect and cancel the auto-open while it's still running (e.g. expanding a link for Waze)
    LaunchedEffect(Unit) {
        while (isAutoOpenEnabled && timeLeft > 0) {
            delay(1000)
            timeLeft--
        }
        if (isAutoOpenEnabled) {
            launcher.open(preferredApp)
            onClose()
        }
    }

    fun choose(open: suspend () -> Unit) {
        isAutoOpenEnabled = false
        scope.launch {
            open()
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A Surface takes the touches on it, so tapping the card doesn't close the chooser
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp, // Stands out from what's behind it, e.g. a map
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.chooser_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isAutoOpenEnabled && timeLeft > 0) {
                        Text(
                            text = stringResource(R.string.auto_opening_msg, timeLeft),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (MapsUtils.isDroppedPinTitle(title) && MapsUtils.isGoogleMapsLink(url)) {
                        Text(
                            text = stringResource(R.string.pin_warning_msg),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    if (launcher.isInstalled(NavigatorLauncher.MAPS_PACKAGE)) {
                        ChooserOption(
                            icon = painterResource(id = R.drawable.ic_map),
                            label = stringResource(R.string.app_maps),
                            isPreferred = preferredApp == ReceiverRepository.APP_MAPS,
                            onClick = { choose { launcher.openInMaps() } }
                        )
                    }

                    if (launcher.isInstalled(NavigatorLauncher.WAZE_PACKAGE)) {
                        ChooserOption(
                            icon = painterResource(id = R.drawable.ic_waze),
                            label = stringResource(R.string.app_waze),
                            isPreferred = preferredApp == ReceiverRepository.APP_WAZE,
                            onClick = { choose { launcher.openInWaze() } }
                        )
                    }

                    ChooserOption(
                        icon = painterResource(id = R.drawable.ic_navigation),
                        label = stringResource(R.string.app_other),
                        isPreferred = preferredApp == ReceiverRepository.APP_OTHER,
                        onClick = { choose { launcher.openInOtherApp() } }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ChooserOption(
                        icon = painterResource(id = R.drawable.ic_content_copy),
                        label = stringResource(R.string.btn_copy),
                        isPreferred = false,
                        onClick = { choose { launcher.copyToClipboard() } }
                    )
                }

                TextButton(
                    onClick = {
                        isAutoOpenEnabled = false
                        onClose()
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    }
}

@Composable
private fun ChooserOption(
    icon: Painter,
    label: String,
    isPreferred: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp).padding(vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPreferred) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isPreferred) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(20.dp))
            Text(text = label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
