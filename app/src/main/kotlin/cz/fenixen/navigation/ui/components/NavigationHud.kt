package cz.fenixen.navigation.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.map.ManeuverIcons
import cz.fenixen.navigation.ui.theme.Hud
import kotlin.math.roundToInt

/** Dark glass panel – the basic building block of the HUD. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Hud.CardShape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Hud.Glass,
        contentColor = Hud.TextPrimary,
        border = Hud.GlassBorder,
        shadowElevation = 16.dp,
        content = content,
    )
}

@Composable
fun ManeuverIcon(maneuver: Maneuver, size: Dp, tint: Color = Color.White) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val argb = android.graphics.Color.argb(
        (tint.alpha * 255).roundToInt(), (tint.red * 255).roundToInt(), (tint.green * 255).roundToInt(), (tint.blue * 255).roundToInt(),
    )
    val bitmap = remember(maneuver.type, maneuver.roundaboutExitCount, px, argb) { ManeuverIcons.bitmap(maneuver, px, argb) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = maneuver.instruction, modifier = Modifier.size(size))
}

/** "300 m" with the unit smaller – like a telemetry readout. */
private fun splitDistance(text: String): Pair<String, String> {
    val i = text.lastIndexOf(' ')
    return if (i > 0) text.substring(0, i) to text.substring(i + 1) else text to ""
}

/**
 * Next maneuver: papaya icon tile, huge distance, street, motorway exit sign and a progress
 * bar that fills as you approach the turn (you *feel* the distance without reading it).
 */
@Composable
fun ManeuverCard(state: NavigationState.Navigating, modifier: Modifier = Modifier) {
    val upcoming = state.upcoming
    val progress = state.progress
    Column(modifier.widthIn(max = 560.dp)) {
        GlassPanel(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = if (state.isRerouting || upcoming == null || progress == null) -1 else progress.upcomingManeuverIndex,
                transitionSpec = {
                    (slideInVertically { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutVertically { -it / 3 } + fadeOut(tween(160)))
                },
                label = "maneuver",
            ) { index ->
                if (index < 0 || upcoming == null || progress == null) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Hud.Papaya, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(if (state.isRerouting) R.string.rerouting else R.string.planning),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                } else {
                    val section = state.route.maneuvers.getOrNull(index - 1)?.lengthMeters ?: 0.0
                    val approach = if (section > 0) (1 - progress.distanceToUpcoming / section).toFloat().coerceIn(0f, 1f) else 0f
                    Column {
                        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(78.dp).clip(RoundedCornerShape(22.dp)).background(Hud.PapayaGradient),
                                contentAlignment = Alignment.Center,
                            ) { ManeuverIcon(upcoming, 58.dp) }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                val (value, unit) = splitDistance(CzechFormat.distance(progress.distanceToUpcoming))
                                Text(
                                    buildAnnotatedString {
                                        append(value)
                                        withStyle(SpanStyle(fontSize = 22.sp, color = Hud.TextSecondary)) { append(" $unit") }
                                    },
                                    style = Hud.BigNumber,
                                    maxLines = 1,
                                )
                                Text(
                                    upcoming.primaryName ?: upcoming.instruction.removeSuffix("."),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Hud.TextPrimary.copy(alpha = 0.92f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ExitSign(upcoming)
                            }
                        }
                        ApproachBar(approach)
                    }
                }
            }
        }
        // „Potom…“ – když další manévr přijde hned po tomhle (do 250 m).
        val following = state.following
        val showThen = !state.isRerouting && upcoming != null && following != null &&
            !upcoming.type.isArrival && upcoming.lengthMeters < 250
        AnimatedVisibility(showThen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (following != null) {
                Surface(
                    color = Hud.GlassStrong,
                    contentColor = Hud.TextPrimary,
                    shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                    border = Hud.GlassBorder,
                    modifier = Modifier.padding(start = 24.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.then).uppercase(), style = Hud.Label)
                        Spacer(Modifier.width(10.dp))
                        ManeuverIcon(following, 24.dp, Hud.Papaya)
                    }
                }
            }
        }
        AnimatedVisibility(state.rerouteFailed && !state.isRerouting) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.reroute_failed),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Czech motorway sign look: green plate, white exit number box. */
@Composable
private fun ExitSign(m: Maneuver) {
    val sign = m.sign ?: return
    if (sign.exitNumber == null && sign.toward.isEmpty()) return
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        sign.exitNumber?.let {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(Hud.MotorwayGreen).padding(horizontal = 3.dp, vertical = 3.dp),
            ) {
                Text(
                    it,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 6.dp),
                    color = Hud.MotorwayGreen,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (sign.toward.isNotEmpty()) {
            Text(
                sign.toward.take(2).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Hud.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApproachBar(fraction: Float) {
    val animated by animateFloatAsState(fraction, tween(900), label = "approach")
    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.07f))) {
        Box(Modifier.fillMaxWidth(animated).fillMaxHeight().background(Hud.PapayaGradient))
    }
}

/**
 * Bottom dock: end · ETA · remaining · overview · voice. A thin bar on top shows trip progress.
 * Live-traffic delay appears as an amber/red chip.
 */
@Composable
fun TripDock(
    state: NavigationState.Navigating,
    voiceEnabled: Boolean,
    onStop: () -> Unit,
    onOverview: () -> Unit,
    onToggleVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    GlassPanel(modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(30.dp)) {
        Column {
            val tripFraction = progress?.let { (it.distanceAlong / state.route.geometry.length.coerceAtLeast(1.0)).toFloat() } ?: 0f
            Box(Modifier.padding(horizontal = 26.dp).padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(tripFraction.coerceIn(0f, 1f)).fillMaxHeight().background(Hud.Papaya))
            }
            Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(R.drawable.ic_close, stringResource(R.string.end_navigation), onStop, container = Color(0xFF3A1416), tint = Color(0xFFFF8A80))
                Spacer(Modifier.width(14.dp))
                if (progress != null) {
                    val eta = System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()
                    Column {
                        Text(stringResource(R.string.label_arrival), style = Hud.Label)
                        Text(CzechFormat.clock(eta), style = Hud.Number.copy(color = Hud.Papaya))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_remaining), style = Hud.Label)
                        Text(CzechFormat.duration(progress.durationRemaining), style = Hud.SmallNumber, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(CzechFormat.distance(progress.distanceRemaining), style = MaterialTheme.typography.bodyMedium, color = Hud.TextSecondary, maxLines = 1)
                            TrafficChip(state.route.traffic?.delaySeconds)
                        }
                    }
                } else {
                    Text(state.destination.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                }
                RoundButton(R.drawable.ic_overview, stringResource(R.string.overview), onOverview)
                Spacer(Modifier.width(8.dp))
                RoundButton(
                    if (voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
                    stringResource(if (voiceEnabled) R.string.mute else R.string.unmute),
                    onToggleVoice,
                    tint = if (voiceEnabled) Hud.TextPrimary else Hud.Papaya,
                )
            }
        }
    }
}

@Composable
fun TrafficChip(delaySeconds: Double?, modifier: Modifier = Modifier) {
    val delay = delaySeconds ?: return
    val minutes = (delay / 60).roundToInt()
    if (minutes < 1) return
    val color = if (minutes >= 10) Hud.Red else Hud.Amber
    Text(
        "+$minutes min",
        modifier = modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
fun RoundButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    container: Color = Color.White.copy(alpha = 0.08f),
    tint: Color = Hud.TextPrimary,
    size: Dp = 50.dp,
) {
    Surface(onClick = onClick, shape = CircleShape, color = container, contentColor = tint, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), description, Modifier.size(22.dp)) }
    }
}

/**
 * Speed gauge: ring fills towards the limit, turns red over it. The Czech limit sign (B 20a)
 * sits on the ring like a badge.
 */
@Composable
fun SpeedGauge(speedMps: Double?, limitKph: Int?, speeding: Boolean, modifier: Modifier = Modifier) {
    val kmh = ((speedMps ?: 0.0) * 3.6).roundToInt()
    val ringColor by animateColorAsState(if (speeding) Hud.Red else Hud.Papaya, label = "ring")
    val fraction by animateFloatAsState(
        (kmh / (limitKph ?: 130).toFloat()).coerceIn(0f, 1.25f) / 1.25f,
        tween(600),
        label = "speed",
    )
    Box(modifier.size(width = 98.dp, height = 84.dp)) {
        GlassPanel(Modifier.size(80.dp).align(Alignment.BottomStart), shape = CircleShape) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(80.dp).padding(6.dp)) {
                    val stroke = 5.dp.toPx()
                    val arc = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(ringColor, 135f, 270f * fraction, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$kmh", style = Hud.Number.copy(fontSize = 28.sp, color = if (speeding) Hud.Red else Hud.TextPrimary))
                    Text("km/h", style = Hud.Label.copy(fontSize = 9.sp, letterSpacing = 0.5.sp))
                }
            }
        }
        if (limitKph != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .border(5.dp, Hud.SignRed, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$limitKph", color = Color.Black, fontSize = if (limitKph >= 100) 14.sp else 17.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Papaya pill shown when the map was panned away from the car. */
@Composable
fun RecenterPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(26.dp), color = Hud.Papaya, contentColor = Color(0xFF1A0D00), shadowElevation = 12.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(20.dp))
            Text(stringResource(R.string.recenter), fontWeight = FontWeight.Bold)
        }
    }
}

/** Nearest incident ahead: "⚠ Nehoda · 1,8 km · +9 min". */
@Composable
fun IncidentChip(state: NavigationState.Navigating, modifier: Modifier = Modifier) {
    val progress = state.progress ?: return
    val next = state.nextIncident ?: return
    val distance = next.distanceAlong - progress.distanceAlong
    if (distance > 15_000) return
    val color = Color(cz.fenixen.navigation.map.PuckBitmaps.incidentColor(next.incident.category))
    Surface(color = Hud.GlassStrong, contentColor = Hud.TextPrimary, shape = RoundedCornerShape(18.dp), border = Hud.GlassBorder, modifier = modifier) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text("!", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                cz.fenixen.navigation.core.traffic.IncidentTexts.label(next.incident.category),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(" · ${CzechFormat.distance(distance.coerceAtLeast(0.0))}", style = MaterialTheme.typography.labelLarge, color = Hud.TextSecondary)
            next.incident.delaySeconds?.let { TrafficChip(it) }
        }
    }
}

/**
 * "Faster route – save 8 min". Accept or dismiss; a draining bar shows how long the offer is valid
 * (after that the turn-off may already be behind us).
 */
@Composable
fun FasterRouteCard(
    offer: cz.fenixen.navigation.core.navigation.FasterRouteOffer,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = androidx.compose.runtime.remember(offer.id) { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(offer.id) {
        remaining.animateTo(0f, tween(60_000, easing = androidx.compose.animation.core.LinearEasing))
    }
    GlassPanel(modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RYCHLEJŠÍ TRASA", style = Hud.Label.copy(color = Hud.Mint))
                    Text(
                        "−${(offer.savingSeconds / 60).roundToInt()} min",
                        style = Hud.Number.copy(fontSize = 26.sp, color = Hud.Mint),
                    )
                    offer.route.summary.takeIf { it.isNotBlank() }?.let {
                        Text("přes $it", style = MaterialTheme.typography.bodyMedium, color = Hud.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundButton(R.drawable.ic_close, stringResource(R.string.cancel), onDismiss)
                Spacer(Modifier.width(8.dp))
                Surface(onClick = onAccept, shape = RoundedCornerShape(20.dp), color = Hud.Mint, contentColor = Color(0xFF00281A), modifier = Modifier.height(50.dp)) {
                    Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text("POUŽÍT", fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(remaining.value).fillMaxHeight().background(Hud.Mint))
            }
        }
    }
}
