package com.ogenrwotdaniel.wakeproof.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitCheckboxButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/** Euler's Lab on the wrist: the phone app's colours, on the old watch background. */
object EulerColors {
    val Background = Color(0xFF0F1417)
    val Accent = Color(0xFFFF7043)
    val Good = Color(0xFF81C784)
    val Muted = Color(0xFFB0BEC5)
    val Surface = Color(0xFF1B2429)
    val Track = Color(0xFF263238)
    val AmbientText = Color(0xFFB0B0B0)
    val AmbientTrack = Color(0xFF3A3A3A)
}

@Composable
fun EulerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = EulerColors.Accent,
            primaryDim = Color(0xFFE0603A),
            primaryContainer = Color(0xFF5C2A1A),
            onPrimary = Color(0xFF1F0D06),
            onPrimaryContainer = Color(0xFFFFDBCF),
            secondary = EulerColors.Good,
            onSecondary = Color(0xFF0B2310),
            surfaceContainerLow = Color(0xFF151D21),
            surfaceContainer = EulerColors.Surface,
            surfaceContainerHigh = Color(0xFF243037),
            onSurface = Color.White,
            onSurfaceVariant = EulerColors.Muted,
            background = EulerColors.Background,
            onBackground = Color.White
        ),
        content = content
    )
}

/**
 * A full-circle progress ring, drawn by hand so it never animates on its own
 * (ambient forbids it) and can run edge to edge on a round face.
 * [ambient]: a thin grey outline only — OLED burn-in and the low-bit mode.
 */
@Composable
fun ProgressRing(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    ambient: Boolean = false
) {
    Canvas(modifier) {
        val stroke = (if (ambient) 2.dp else strokeWidth).toPx()
        val inset = stroke / 2
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = if (ambient) EulerColors.AmbientTrack else EulerColors.Track,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke)
        )
        val share = progress ?: return@Canvas
        if (share <= 0f) return@Canvas
        rotate(-90f) {
            if (ambient) {
                drawArc(
                    color = EulerColors.AmbientText,
                    startAngle = 0f, sweepAngle = 360f * share, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke)
                )
            } else {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to color.copy(alpha = 0.35f),
                        share.coerceAtLeast(0.01f) to color,
                        1f to color
                    ),
                    startAngle = 0f, sweepAngle = 360f * share, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/** Finished, current and coming focus blocks as dots. */
@Composable
fun BlockDots(done: Int, target: Int, isFocus: Boolean, color: Color) {
    if (target !in 1..FocusFaces.MAX_DOTS) return
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until target) {
            val fill = when {
                i < done -> color
                i == done && isFocus -> color.copy(alpha = 0.45f)
                else -> EulerColors.Track
            }
            Box(Modifier.size(if (i == done && isFocus) 9.dp else 7.dp).background(fill, CircleShape))
        }
    }
}

/** The Focus page while a session runs (watch or phone): ring, countdown, blocks, tree. */
@Composable
fun RunningFocus(
    face: RunningFace,
    notice: String?,
    busy: Boolean,
    time: (Long) -> String,
    onAction: (FocusAction) -> Unit
) {
    val ringColor = if (face.isFocus) EulerColors.Accent else EulerColors.Good
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ProgressRing(face.progress, ringColor, Modifier.fillMaxSize().padding(4.dp))
        Column(
            Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                face.phaseLabel.uppercase(),
                color = ringColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Text(
                face.countdown,
                color = Color.White,
                fontSize = 46.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            if (face.paused) Text("(paused)", color = EulerColors.Muted, fontSize = 12.sp)
            notice?.let {
                Text(it, color = EulerColors.Accent, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
            face.subject?.let {
                Text(
                    it,
                    color = EulerColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(4.dp))
            BlockDots(face.blocksDone, face.blocksTarget, face.isFocus, ringColor)
            val detail = listOfNotNull(
                face.blockLine,
                face.nextAt?.let { at -> "${face.nextLabel} at ${time(at)}" }
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, color = EulerColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
            if (face.actions.isEmpty()) {
                face.tree?.let { Text("${it.glyph} ${it.label}", color = EulerColors.Muted, fontSize = 12.sp) }
            } else {
                Spacer(Modifier.height(6.dp))
                face.actions.forEach { action ->
                    CompactButton(onClick = { onAction(action) }, enabled = !busy) { Text(action.label) }
                }
            }
        }
    }
}

/**
 * The Focus page dimmed (ambient): grey on black, a thin outline ring, whole
 * minutes, no controls and nothing animated. Updated by the once-a-minute tick.
 */
@Composable
fun AmbientFocus(face: RunningFace, time: (Long) -> String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        ProgressRing(face.progress, EulerColors.AmbientText, Modifier.fillMaxSize().padding(4.dp), ambient = true)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                face.phaseLabel + if (face.paused) " (paused)" else "",
                color = EulerColors.AmbientText,
                fontSize = 13.sp
            )
            Text(face.countdown, color = EulerColors.AmbientText, fontSize = 40.sp)
            val detail = listOfNotNull(
                face.blockLine,
                face.nextAt?.let { at -> "${face.nextLabel} at ${time(at)}" }
            ).joinToString("\n")
            if (detail.isNotEmpty()) {
                Text(detail, color = EulerColors.AmbientText, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** What the idle Focus page and the Progress page read. */
data class TodayNumbers(
    val statsPresent: Boolean,
    val streak: Int,
    val asterisk: Boolean,
    val focusMinutes: Int,
    val goal: Int,
    val unsyncedBlocks: Int,
    val cloudLine: String?,
    /**
     * Reserved for a later "walls status" line (the watch's app walls); null
     * draws nothing. Fill it from the activity when that lands.
     */
    val wallsLine: String? = null
)

/** "🔥 12-day streak · Focus 1h 05m / 6h", as the old idle screen said it. */
private fun focusLine(n: TodayNumbers): String =
    if (!n.statsPresent) "Focus today ${formatFocusMinutes(n.focusMinutes)}"
    else "Focus ${formatFocusMinutes(n.focusMinutes)}" +
        if (n.goal > 0) " / ${formatFocusMinutes(n.goal)}" else ""

/** The Focus page with nothing running: today at a glance, then the start options. */
@Composable
fun IdleFocus(
    numbers: TodayNumbers,
    actions: List<IdleAction>,
    notice: String?,
    busy: Boolean,
    onAction: (IdleAction) -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec)
                ) { Text("Euler's Lab", color = EulerColors.Accent) }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().transformedHeight(this, spec).padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (numbers.statsPresent) {
                        Text(
                            "🔥 ${numbers.streak}-day streak",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val met = FocusFaces.goalMet(numbers.focusMinutes, numbers.goal)
                    Text(
                        focusLine(numbers) + if (met) " ✓" else "",
                        color = if (met) EulerColors.Good else EulerColors.Muted,
                        fontSize = 13.sp
                    )
                    notice?.let {
                        Text(it, color = EulerColors.Accent, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            items(actions) { action ->
                val label = when (action) {
                    is IdleAction.Start -> action.session.label
                    IdleAction.StartOnPhone -> "Start on phone"
                    IdleAction.SignIn -> "Sign in to cloud"
                }
                val secondary = when (action) {
                    is IdleAction.Start -> "Focus on the watch"
                    IdleAction.StartOnPhone -> "Walls, forest and sounds"
                    IdleAction.SignIn -> null
                }
                Button(
                    onClick = { onAction(action) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    colors = if (action is IdleAction.Start) ButtonDefaults.buttonColors()
                    else ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(label) },
                    secondaryLabel = secondary?.let { { Text(it) } }
                )
            }
            item {
                Text(
                    "Swipe for Today and Progress",
                    color = EulerColors.Muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).padding(top = 6.dp)
                )
            }
        }
    }
}

/** Progress: the streak, and today's focus against the goal as a ring — green once met. */
@Composable
fun ProgressPage(numbers: TodayNumbers) {
    ScreenScaffold { _ ->
        val met = FocusFaces.goalMet(numbers.focusMinutes, numbers.goal)
        val color = if (met) EulerColors.Good else EulerColors.Accent
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProgressRing(
                FocusFaces.goalShare(numbers.focusMinutes, numbers.goal),
                color,
                Modifier.fillMaxSize().padding(4.dp),
                strokeWidth = 12.dp
            )
            Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (numbers.statsPresent) {
                    Text("🔥", fontSize = 30.sp)
                    Text(
                        "${numbers.streak}${if (numbers.asterisk) "*" else ""}",
                        color = EulerColors.Accent,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("day streak", color = EulerColors.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    focusLine(numbers),
                    color = if (met) EulerColors.Good else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                if (met) Text("Goal met ✓", color = EulerColors.Good, fontSize = 12.sp)
                listOfNotNull(
                    FocusFaces.unsyncedLine(numbers.unsyncedBlocks),
                    numbers.cloudLine,
                    numbers.wallsLine
                ).forEach {
                    Text(
                        it,
                        color = EulerColors.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Today: the phone's calendar for today; a title opens its confirm, the box ticks it on the phone. */
@Composable
fun AgendaPage(controller: AgendaController) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val empty = FocusFaces.agendaEmptyText(controller.agenda, controller.today)
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec)
                ) { Text("Today", color = EulerColors.Accent) }
            }
            controller.status?.let { status ->
                item {
                    Text(
                        status,
                        color = EulerColors.Muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)
                    )
                }
            }
            if (empty != null) {
                item {
                    Text(
                        empty,
                        color = EulerColors.Muted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).padding(top = 8.dp)
                    )
                }
            } else {
                items(controller.events, key = { it.key }) { event ->
                    SplitCheckboxButton(
                        checked = event.done,
                        onCheckedChange = { controller.tick(event) },
                        toggleContentDescription = if (event.done) "Ticked off" else "Tick off",
                        onContainerClick = { controller.onTitle(event) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = {
                            Text(
                                event.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (event.done) EulerColors.Muted else Color.White,
                                textDecoration = if (event.done) TextDecoration.LineThrough else null
                            )
                        },
                        secondaryLabel = {
                            Text("${controller.time(event.beginMillis)} – ${controller.time(event.endMillis)}")
                        }
                    )
                }
            }
        }
    }
    AgendaDialogs(controller)
}

/** The confirm ("Focus until 15:00?") or the refusal, over the list. */
@Composable
fun AgendaDialogs(controller: AgendaController) {
    when (val dialog = controller.dialog) {
        null -> Unit
        is AgendaDialog.Confirm -> AlertDialog(
            visible = true,
            onDismissRequest = controller::dismiss,
            title = {
                Text("Focus until ${controller.time(dialog.event.endMillis)} (${WatchAgenda.lengthLabel(dialog.minutes)})?")
            },
            text = { Text("\"${dialog.event.title}\"\nOnce it starts it can't be paused or stopped.") },
            edgeButton = {
                EdgeButton(onClick = { controller.start(dialog.event) }) { Text("Start") }
            }
        ) {
            item {
                Button(
                    onClick = controller::dismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Not now") }
                )
            }
        }
        is AgendaDialog.Refused -> AlertDialog(
            visible = true,
            onDismissRequest = controller::dismiss,
            title = { Text(controller.refusalText(dialog.reason)) },
            text = { Text("\"${dialog.event.title}\"") },
            edgeButton = {
                EdgeButton(onClick = controller::dismiss) { Text("Back") }
            }
        ) {
            if (!dialog.event.done) {
                item {
                    Button(
                        onClick = { controller.tickFromDialog(dialog.event) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tick it off") }
                    )
                }
            }
        }
    }
}
