package com.example.receiver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    // Room for the countdown only if there is one, kept until the chooser closes so that nothing moves
    val hasCountdown = remember { isAutoOpenEnabled }

    // The card fades in when the chooser opens
    val cardAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { cardAlpha.animateTo(1f, tween(durationMillis = 400)) }

    // The apps chosen in the settings, all known before the chooser shows up so that no button
    // moves under the finger. The one that opens by itself comes first, and is always shown.
    val context = LocalContext.current
    val shownApps = settings.chooserApps + preferredApp
    val otherApps = remember {
        shownApps.mapNotNull { NavigatorLauncher.loadApp(context, it) }
            .sortedBy { it.label.lowercase() }
            .associateBy { it.packageName }
    }
    val apps = remember {
        buildList {
            if (ReceiverRepository.APP_MAPS in shownApps && launcher.isInstalled(NavigatorLauncher.MAPS_PACKAGE)) {
                add(ReceiverRepository.APP_MAPS)
            }
            if (ReceiverRepository.APP_WAZE in shownApps && launcher.isInstalled(NavigatorLauncher.WAZE_PACKAGE)) {
                add(ReceiverRepository.APP_WAZE)
            }
            addAll(otherApps.keys)
        }.sortedByDescending { it == preferredApp }
    }

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
        val cardShape = RoundedCornerShape(28.dp)
        Surface(
            shape = cardShape,
            // The same colors as the history: its background here, its items' color for the buttons
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.widthIn(max = 400.dp).graphicsLayer {
                alpha = cardAlpha.value
                // Cast by the layer that fades in, so that the shadow fades in with the card.
                // It makes the card stand out from what's behind it, e.g. a map.
                shadowElevation = 12.dp.toPx()
                shape = cardShape
            },
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

                if (hasCountdown) {
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
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (ReceiverRepository.APP_WAZE in apps && MapsUtils.isDroppedPinTitle(title) && MapsUtils.isGoogleMapsLink(url)) {
                        Text(
                            text = stringResource(R.string.pin_warning_msg),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    for (app in apps) {
                        when (app) {
                            ReceiverRepository.APP_MAPS -> ChooserOption(
                                icon = painterResource(id = R.drawable.ic_map),
                                label = stringResource(R.string.app_maps),
                                isPreferred = app == preferredApp,
                                onClick = { choose { launcher.openInMaps() } }
                            )
                            ReceiverRepository.APP_WAZE -> ChooserOption(
                                icon = painterResource(id = R.drawable.ic_waze),
                                label = stringResource(R.string.app_waze),
                                isPreferred = app == preferredApp,
                                onClick = { choose { launcher.openInWaze() } }
                            )
                            else -> OtherAppOption(
                                otherApps.getValue(app),
                                isPreferred = app == preferredApp,
                                onClick = { choose { launcher.openInApp(app) } }
                            )
                        }
                    }

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
private fun OtherAppOption(app: OtherApp, isPreferred: Boolean, onClick: () -> Unit) {
    val icon = remember(app) { BitmapPainter(app.icon.asImageBitmap()) }
    ChooserOption(icon = icon, label = app.label, isPreferred = isPreferred, onClick = onClick, tintIcon = false)
}

@Composable
private fun ChooserOption(
    icon: Painter,
    label: String,
    isPreferred: Boolean,
    onClick: () -> Unit,
    tintIcon: Boolean = true, // Not for app icons, which have their own colors
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp).padding(vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPreferred) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = if (isPreferred) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (tintIcon) LocalContentColor.current else Color.Unspecified,
            )
            Spacer(modifier = Modifier.width(20.dp))
            // An app's name may be long
            Text(text = label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
