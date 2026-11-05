package com.warden.blocker.ui.intercept

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.warden.blocker.accessibility.ForegroundGate
import com.warden.blocker.allow.AllowBlockNotice
import com.warden.blocker.block.LimitReason
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.stats.StatsRecorder
import com.warden.blocker.ui.LocalMotion
import com.warden.blocker.ui.Motion
import com.warden.blocker.ui.hideOverlayWindows
import com.warden.blocker.ui.theme.WardenTheme
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown over a blocked app. Renders one of three experiences depending on the decision:
 * a hard block, a limit-reached notice, or the mindful pause that can grant timed access.
 */
class InterceptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Anti-tapjacking: a malicious overlay must not be able to trick taps on "Continue"
        // (which grants access) or dismiss the block.
        window.decorView.filterTouchesWhenObscured = true
        hideOverlayWindows(window, true)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_BLOCK
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        val reason = intent.getStringExtra(EXTRA_REASON)?.let { runCatching { LimitReason.valueOf(it) }.getOrNull() }
        val featureLabel = intent.getStringExtra(EXTRA_FEATURE_LABEL)
        val allowBlockLabel = AllowBlockNotice.packageName?.let { AppsHelper.labelFor(this, it) }
        // Count each interception once — not again when the Fold unfolds or the screen rotates.
        if (savedInstanceState == null) recordIntercept(kind, itemId, featureLabel)

        setContent {
            WardenTheme {
                var item by remember { mutableStateOf<BlockedItem?>(null) }
                // Until the entry is read, show the calm background instead of a wrong screen
                // for a moment; then the right one fades in.
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(itemId) {
                    item = wardenContainer.repository.item(itemId)
                    loaded = true
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val needsItem = kind != KIND_FEATURE && kind != KIND_WEBSITE && !AllowBlockNotice.isAllowBlock(itemId)
                    Crossfade(!needsItem || loaded, animationSpec = tween(Motion.MEDIUM), label = "intercept") { ready ->
                        if (!ready) return@Crossfade
                        val current = item
                        when {
                            kind == KIND_FEATURE -> FeatureBlockScreen(featureLabel, ::goHome)
                            kind == KIND_WEBSITE -> CenteredMessage(
                                title = "${featureLabel ?: "This website"} is blocked",
                                body = "Warden+'s website filter isn't running (for example another VPN like Tailscale " +
                                    "is on), so Browser mode blocks this site in the browser instead.",
                                onHome = ::goHome,
                            )
                            // "Allow only" / blocked category: no blocklist entry behind it.
                            AllowBlockNotice.isAllowBlock(itemId) -> CenteredMessage(
                                title = AllowBlockNotice.title(itemId),
                                body = AllowBlockNotice.body(itemId, allowBlockLabel),
                                onHome = ::goHome,
                            )
                            kind == KIND_PAUSE && current != null -> PauseScreen(
                                item = current,
                                onContinue = { minutes -> grantAndEnter(current, minutes) },
                                onCancel = ::goHome,
                            )
                            kind == KIND_LIMIT && current != null -> LimitScreen(current, reason, ::goHome)
                            else -> HardBlockScreen(current, ::goHome)
                        }
                    }
                }
            }
        }
    }

    /** Feeds the stats dashboard; in-app feature blocks are keyed by feature, not package. */
    private fun recordIntercept(kind: String, itemId: Long, featureLabel: String?) {
        lifecycleScope.launch {
            val key = if (kind == KIND_FEATURE) {
                "feature:${featureLabel ?: "feed"}"
            } else if (AllowBlockNotice.isAllowBlock(itemId)) {
                AllowBlockNotice.packageName
            } else {
                wardenContainer.repository.item(itemId)?.takeIf { it.type == BlockType.APP }?.value
            }
            key?.let { StatsRecorder.get(this@InterceptActivity).recordAppBlocked(it) }
        }
    }

    private fun grantAndEnter(item: BlockedItem, minutes: Int) {
        lifecycleScope.launch {
            wardenContainer.repository.grantAccess(item, minutes)
            finish() // return to the app that is waiting behind us; the grant lets it through
        }
    }

    private fun goHome() {
        // Widgets of blocked apps live on the home screen; give it a moment before judging again.
        ForegroundGate.shared.noteWentHome(SystemClock.uptimeMillis())
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    @Deprecated("Back should return home, never silently enter the app")
    override fun onBackPressed() = goHome()

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_REASON = "reason"
        const val EXTRA_FEATURE_LABEL = "feature_label"
        const val KIND_PAUSE = "pause"
        const val KIND_BLOCK = "block"
        const val KIND_LIMIT = "limit"
        const val KIND_FEATURE = "feature"
        /** Browser mode: a blocked website in a browser's address bar ([EXTRA_FEATURE_LABEL] = host). */
        const val KIND_WEBSITE = "website"
    }
}

@Composable
private fun FeatureBlockScreen(featureLabel: String?, onHome: () -> Unit) {
    CenteredMessage(
        title = "${featureLabel ?: "That feed"} is off-limits",
        body = "Warden+ is keeping you out of ${featureLabel ?: "this feed"}. The rest of the app still works — just not the endless scroll.",
        onHome = onHome,
    )
}

@Composable
private fun PauseScreen(item: BlockedItem, onContinue: (Int) -> Unit, onCancel: () -> Unit) {
    var remaining by remember { mutableStateOf(item.pauseSeconds.coerceAtLeast(1)) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1000); remaining-- }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BreathingCircle(220.dp, from = 0.72f, to = 1.08f) {
            Text(
                if (remaining > 0) "Breathe\n${remaining}s" else "Ready?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(40.dp))
        Text(
            item.customPrompt?.takeIf { it.isNotBlank() } ?: "Why are you opening ${item.label}?",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        // The choices fade in once the breath is over.
        AnimatedContent(
            remaining > 0,
            transitionSpec = { fadeIn(tween(Motion.MEDIUM * 2)) togetherWith fadeOut(tween(Motion.SHORT)) },
            label = "pauseChoices",
        ) { waiting ->
            if (waiting) {
                Text(
                    "Take a moment. If you still want in, you can continue in a second.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Continue for…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(1, 5, 15).forEach { min ->
                            OutlinedButton(onClick = { onContinue(min) }) { Text("$min min") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onCancel) { Text("Not now — take me home") }
    }
}

/**
 * A soft circle in the theme colour that slowly grows and shrinks (one breath every 8 s) behind
 * [content]. Still with "Remove animations". Only drawn while the screen is shown.
 */
@Composable
private fun BreathingCircle(size: Dp, from: Float, to: Float, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val breath = if (motion) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = from,
            targetValue = to,
            animationSpec = infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "scale",
        )
    } else {
        null
    }
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Box(contentAlignment = Alignment.Center) {
        // Read in the draw phase only: the breath doesn't recompose the screen.
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = breath?.value ?: 1f
                    scaleX = s
                    scaleY = s
                }
                .background(color, CircleShape),
        )
        content()
    }
}

@Composable
private fun HardBlockScreen(item: BlockedItem?, onHome: () -> Unit) {
    CenteredMessage(
        title = "Blocked by Warden+",
        body = item?.let { "You asked Warden+ to keep you off ${it.label}. The urge passes — you've got this." }
            ?: "This is blocked right now.",
        onHome = onHome,
    )
}

@Composable
private fun LimitScreen(item: BlockedItem, reason: LimitReason?, onHome: () -> Unit) {
    val body = when (reason) {
        LimitReason.OPENS -> "You've used up today's opens for ${item.label}. It resets tomorrow."
        LimitReason.TIME -> "You've hit today's time limit for ${item.label}. It resets tomorrow."
        LimitReason.COOLDOWN -> "You just used ${item.label}. Give it a little while before the next visit."
        null -> "${item.label} is limited right now."
    }
    CenteredMessage(title = "Limit reached", body = body, onHome = onHome)
}

@Composable
private fun CenteredMessage(title: String, body: String, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same calm breathing as the pause screen, smaller: a shield in a soft circle.
        BreathingCircle(120.dp, from = 0.9f, to = 1.06f) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onHome) { Text("Back to home") }
    }
}
