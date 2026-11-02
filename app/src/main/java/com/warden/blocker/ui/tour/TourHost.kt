package com.warden.blocker.ui.tour

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** False when "Remove animations" / animator scale 0 is set: then the guide jumps instead of gliding. */
fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/**
 * Hosts the guides on top of [content] (the whole app incl. the bottom bar). [currentRoute] is
 * the NavHost's current route; [navigate] opens a route like a user would (tab tap or button).
 */
@Composable
fun TourHost(currentRoute: String?, navigate: (String) -> Unit, content: @Composable () -> Unit) {
    val vm: TourViewModel = viewModel()
    val controller = vm.controller
    val registry = remember { TourRegistry() }
    val run by controller.run.collectAsState()
    val route = rememberUpdatedState(currentRoute)
    val onNavigate by rememberUpdatedState(navigate)

    CompositionLocalProvider(LocalTourRegistry provides registry, LocalTourController provides controller) {
        Box(Modifier.fillMaxSize()) {
            // While a guide runs, TalkBack reads only the guide, not the dimmed screen behind it,
            // and no touch reaches the app: taken before any button or list below sees it, so
            // tapping through the guide can't add apps or flip switches (only the guide reacts).
            val guiding = run != null
            Box(
                (if (guiding) Modifier.clearAndSetSemantics { } else Modifier)
                    .pointerInput(guiding) {
                        if (!guiding) return@pointerInput
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
            ) { content() }
            run?.let { TourOverlay(it, vm, registry, route) { r -> onNavigate(r) } }
            PageTipsOffer(controller, currentRoute, running = run != null)
        }
    }
    ResumeGettingStarted(controller, route)

    val notice by controller.emptyNotice.collectAsState()
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = controller::dismissEmptyNotice,
            title = { Text("Nothing to show") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = controller::dismissEmptyNotice) { Text("OK") } },
        )
    }
}

private sealed interface Phase {
    data object Finding : Phase
    data class Path(val hop: Hop) : Phase
    data class Showing(val display: StepDisplay) : Phase
    /** The step is behind the Settings PIN lock: a card in the middle says so (G3/G4). */
    data object Locked : Phase
}

@Composable
private fun TourOverlay(
    run: TourRun,
    vm: TourViewModel,
    registry: TourRegistry,
    route: State<String?>,
    navigate: (String) -> Unit,
) {
    val controller = vm.controller
    // A run that points nowhere would leave the app blocked with no card: end it.
    val step = Tours.byId(run.tourId)?.steps?.getOrNull(run.index)
    if (step == null) {
        LaunchedEffect(run) { vm.controller.skip() }
        return
    }
    val tour = Tours.byId(run.tourId)!!
    val context = LocalContext.current
    val animate = remember(run.tourId) { animationsEnabled(context) }

    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(Size.Zero) }
    val spot = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var spotOn by remember { mutableStateOf(false) }
    var placeFor by remember { mutableStateOf<Rect?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Finding) }
    val finger = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var fingerOn by remember { mutableStateOf(false) }
    val ripple = remember { Animatable(1f) }
    var replays by remember { mutableIntStateOf(0) }
    var replayRequested by remember { mutableStateOf(false) }
    var canReplay by remember { mutableStateOf(false) }

    suspend fun moveSpot(rootRect: Rect) {
        val r = rootRect.translate(-origin)
        placeFor = r
        if (animate && spotOn) spot.animateTo(r, tween(450, easing = FastOutSlowInEasing)) else spot.snapTo(r)
        spotOn = true
    }

    suspend fun tap(rootPoint: Offset) {
        val p = rootPoint - origin
        if (!fingerOn) {
            finger.snapTo(if (spotOn) spot.value.center else Offset(overlaySize.width / 2, overlaySize.height * 0.45f))
            fingerOn = true
        }
        finger.animateTo(p, tween(750, easing = FastOutSlowInEasing))
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(450))
        delay(120)
    }

    LaunchedEffect(run.tourId, run.index, replays) {
        phase = Phase.Finding
        // Right after rotating/folding the NavHost hasn't restored its screen yet (route null):
        // planning then would replay the way to a screen that is already open.
        withTimeoutOrNull(1500) { snapshotFlow { route.value }.first { it != null } }
        val isReplay = replayRequested.also { replayRequested = false }
        if (isReplay) {
            // Back to where the way started, without animation, then show it again.
            vm.pathOrigin?.let { start ->
                for (hop in TourPath.plan(route.value, start)) { navigate(hop.route); awaitRoute(route, hop.route) }
            }
        }
        val hops = step.route?.let { TourPath.plan(route.value, it) }.orEmpty()
        val showPath = animate && hops.isNotEmpty() && (run.forward || isReplay)
        if (!isReplay) {
            canReplay = showPath
            if (showPath) vm.pathOrigin = route.value
        }
        var lockedOut = false
        for (hop in hops) {
            // Settings locked with the PIN: never walk past the lock into the pages behind it.
            if (route.value == TourRoutes.SETTINGS && !TourPath.isTab(hop.route) && registry.find(TourIds.SETTINGS_LOCKED, 300) != null) {
                lockedOut = true
                break
            }
            if (showPath) {
                phase = Phase.Path(hop)
                registry.find(hop.target, 800)?.let { r ->
                    moveSpot(r)
                    tap(r.center)
                }
            }
            navigate(hop.route)
            awaitRoute(route, hop.route)
        }
        fingerOn = false
        val target = if (lockedOut) null else step.target?.let { registry.find(it, 1500) }
        if (target == null && TourLogic.behindPin(step.route) && (lockedOut || registry.isComposed(TourIds.SETTINGS_LOCKED))) {
            // One card for all of them; the later steps behind the lock are left out.
            controller.markMissing(TourLogic.lockedAhead(tour.steps, run.index))
            spotOn = false
            placeFor = null
            phase = Phase.Locked
            return@LaunchedEffect
        }
        when (val display = TourLogic.display(step, target != null)) {
            StepDisplay.SKIP -> { controller.targetMissing(); return@LaunchedEffect }
            // Text first, so it changes while the spotlight is still gliding over.
            StepDisplay.SPOTLIGHT -> { phase = Phase.Showing(display); moveSpot(target!!) }
            StepDisplay.CENTER -> { spotOn = false; placeFor = null; phase = Phase.Showing(display) }
        }
        if (target != null) {
            // Only once this screen has really drawn (its own target is there), and settled.
            delay(500)
            controller.markMissing(TourLogic.missingAhead(tour.steps, run.index, route.value, registry::isComposed))
        }
        // Keep up with the target while it moves (scrolling into view, folding, rotating).
        val targetId = step.target
        if (target != null && targetId != null) snapshotFlow { registry.rect(targetId) }.filterNotNull().collect { moveSpot(it) }
    }

    // Ending a guide is always asked first: one stray tap on "Skip" (its touch area reaches past
    // the title) used to end it silently, and the next taps then landed in the app (S2, N13).
    var confirmEnd by remember(run.tourId, run.index) { mutableStateOf(false) }
    BackHandler {
        when (TourLogic.backPress(run, confirmEnd)) {
            BackPress.KEEP_GOING -> confirmEnd = false
            BackPress.STEP_BACK -> controller.back()
            BackPress.ASK_END -> confirmEnd = true
        }
    }

    val scrim = Color.Black.copy(alpha = 0.62f)
    val ring = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                overlaySize = Size(it.size.width.toFloat(), it.size.height.toFloat())
            },
    ) {
        // Dimmed screen with the target cut out. It swallows every touch: only the guide reacts.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                .clearAndSetSemantics { },
        ) {
            drawRect(scrim)
            if (spotOn) {
                val pad = 6.dp.toPx()
                val r = spot.value.inflate(pad)
                val corner = CornerRadius(14.dp.toPx())
                drawRoundRect(Color.Black, r.topLeft, r.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(ring, r.topLeft, r.size, corner, style = Stroke(2.5.dp.toPx()))
            }
            if (fingerOn && ripple.value < 1f) {
                drawCircle(Color.White.copy(alpha = 0.55f * (1f - ripple.value)), (10 + 26 * ripple.value).dp.toPx(), finger.value)
            }
        }
        if (fingerOn) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    // The fingertip of the 24-unit glyph is at (11.5, 7.5) → 40 dp: (19, 12.5).
                    .offset { IntOffset((finger.value.x - 19.dp.toPx()).roundToInt(), (finger.value.y - 12.5.dp.toPx()).roundToInt()) }
                    .size(40.dp)
                    .background(ring.copy(alpha = 0.85f), CircleShape)
                    .padding(4.dp),
            )
        }
        val (position, total) = TourLogic.progress(run, tour.steps.size)
        CardLayout(placeFor, roomy = step.guide != null && phase is Phase.Showing) {
            TourCard(
                tour = tour,
                step = step,
                position = position,
                total = total,
                phase = phase,
                animate = animate,
                canReplay = canReplay,
                isLast = TourLogic.next(run, tour.steps.size) == null,
                canGoBack = TourLogic.back(run) != null,
                onBack = controller::back,
                onNext = controller::next,
                confirmingEnd = confirmEnd,
                onSkip = { confirmEnd = true },
                onKeepGoing = { confirmEnd = false },
                onEnd = controller::skip,
                onReplay = { replayRequested = true; replays++ },
            )
        }
    }
}

/** Places the guide card: a sheet on narrow screens, next to [target] on wide ones. */
@Composable
private fun CardLayout(target: Rect?, roomy: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val wide = w.toDp() >= 600.dp
        val gap = 14.dp.toPx()
        val cardWidth = if (wide) minOf(400.dp.roundToPx(), w - 2 * margin) else w - 2 * margin
        // A sheet takes the roomier side of the target, so it doesn't cover it; it scrolls inside.
        // A mini settings animation needs room even if the sheet then overlaps a tall target.
        val freeBeside = target?.let { maxOf(it.top, h - it.bottom) - gap - margin } ?: (h * 0.62f)
        val maxHeight = if (wide) (h * 0.8f).roundToInt() else freeBeside.coerceIn(h * (if (roomy) 0.56f else 0.36f), h * 0.7f).roundToInt()
        val card = measurables.first().measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth, maxHeight = maxHeight))
        val placement = placeCard(
            target,
            Size(w.toFloat(), h.toFloat()),
            Size(card.width.toFloat(), card.height.toFloat()),
            wide,
            margin.toFloat(),
            gap,
        )
        layout(w, h) { card.place(placement.offset.x.roundToInt(), placement.offset.y.roundToInt()) }
    }
}

@Composable
private fun TourCard(
    tour: Tour,
    step: TourStep,
    position: Int,
    total: Int,
    phase: Phase,
    animate: Boolean,
    canReplay: Boolean,
    isLast: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    confirmingEnd: Boolean,
    onSkip: () -> Unit,
    onKeepGoing: () -> Unit,
    onEnd: () -> Unit,
    onReplay: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.semantics { paneTitle = "Guide: ${tour.title}" },
    ) {
        // Header and buttons stay put; only the middle scrolls when space is short.
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${tour.title} · $position/$total",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (!confirmingEnd) TextButton(onClick = onSkip) { Text("Skip") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        confirmingEnd -> {
                            Text("End this guide?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                            Text("You can start it again any time in Settings › Help.", style = MaterialTheme.typography.bodyMedium)
                        }
                        phase is Phase.Path -> {
                            Text("This is how you get there", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                            Text(phase.hop.hint, style = MaterialTheme.typography.bodyMedium)
                        }
                        phase == Phase.Finding -> Text("…", style = MaterialTheme.typography.titleMedium)
                        phase == Phase.Locked -> {
                            Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                            Text(
                                "Settings are locked with your PIN, so this part can't be shown now. Unlock Settings, " +
                                    "then start this guide again from Settings › Help.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        phase is Phase.Showing -> {
                            Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                            Text(step.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (phase is Phase.Showing && !confirmingEnd) {
                    step.guide?.let { guide ->
                        SettingsPathAnimation(guide, animate, Modifier.fillMaxWidth().padding(end = 8.dp))
                        guide.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                    if (canReplay) {
                        TextButton(onClick = onReplay) {
                            Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Show me again")
                        }
                    }
                }
            }
            // The way into Android settings is the point of such a step: always in sight.
            val openLabel = step.guide?.openLabel?.takeIf { phase is Phase.Showing && !confirmingEnd }
            if (openLabel != null) {
                Button(onClick = { openSettingsGuide(context, step.guide) }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) { Text(openLabel) }
            }
            if (confirmingEnd) Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEnd) { Text("End guide") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onKeepGoing) { Text("Keep going") }
            } else Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ready = phase is Phase.Showing || phase is Phase.Locked
                if (canGoBack) OutlinedButton(onClick = onBack, enabled = ready) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onNext, enabled = ready) { Text(if (isLast) "Done" else "Next") }
            }
        }
    }
}

/** Continues "Getting started" if the app was closed half-way through it. (It is offered at the end of the setup wizard.) */
@Composable
private fun ResumeGettingStarted(controller: TourController, route: State<String?>) {
    val records by controller.records.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val loaded = records != null
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        val status = controller.status(Tours.GETTING_STARTED)
        if (status != TourStatus.IN_PROGRESS) return@LaunchedEffect
        snapshotFlow { route.value }.first { it == TourRoutes.HOME }
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        delay(1200)
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        if (controller.run.value == null) controller.start(Tours.GETTING_STARTED, resume = true)
    }
}

/** A small, dismissible "Tips for this page?" chip on the first visit of a page. */
@Composable
private fun PageTipsOffer(controller: TourController, route: String?, running: Boolean) {
    val records by controller.records.collectAsState()
    val seen by controller.pagesSeen.collectAsState()
    var offered by remember { mutableStateOf<Tour?>(null) }
    LaunchedEffect(route, records != null, seen != null) {
        offered = null
        val page = Tours.forPage(route)
        val pagesSeen = seen ?: return@LaunchedEffect
        if (records == null || route == null) return@LaunchedEffect
        delay(700)
        val offer = TourLogic.shouldOfferPageTips(
            controller.status(Tours.GETTING_STARTED), route in pagesSeen, controller.run.value != null, page != null,
        )
        if (!offer) return@LaunchedEffect
        controller.markPageSeen(route)
        offered = page
        delay(9000)
        offered = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = offered != null && !running,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Tips for this page?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    TextButton(onClick = { offered?.let { controller.start(it.id) }; offered = null }) { Text("Show") }
                    IconButton(onClick = { offered = null }) { Icon(Icons.Filled.Close, contentDescription = "No thanks") }
                }
            }
        }
    }
}

private suspend fun awaitRoute(route: State<String?>, target: String) {
    withTimeoutOrNull(2500) { snapshotFlow { route.value }.first { it == target } }
}

/** Waits for [id] to be composed, scrolls it into view and returns where it is; null if it never shows. */
private suspend fun TourRegistry.find(id: String, timeoutMs: Long): Rect? {
    withTimeoutOrNull(timeoutMs) { snapshotFlow { isComposed(id) }.first { it } } ?: return null
    bringIntoView(id)
    return withTimeoutOrNull(700) { snapshotFlow { rect(id) }.filterNotNull().first() }
}
