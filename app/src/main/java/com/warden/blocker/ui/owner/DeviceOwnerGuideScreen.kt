package com.warden.blocker.ui.owner

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.warden.blocker.ui.SubScreenTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device-owner setup guide (docs/DEVICE_OWNER.md, copied into the APK's assets) as a
 * full screen inside Warden+ — before, "Step-by-step guide" opened GitHub in Chrome, which
 * Warden+ itself may block (S3). Back or the arrow closes it.
 */
@Composable
fun DeviceOwnerGuideScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<GuideMarkdown.Block>?>(null) }
    LaunchedEffect(Unit) {
        blocks = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
                .map(GuideMarkdown::parse)
                .getOrElse { listOf(GuideMarkdown.Block.Paragraph("The guide couldn't be loaded. It is also in the Warden+ repository: docs/DEVICE_OWNER.md")) }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                SubScreenTopBar("Step-by-step guide", onBack = onClose)
                SelectionContainer {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        blocks?.forEach { GuideBlock(it) }
                    }
                }
            }
        }
    }
}

private const val ASSET = "DEVICE_OWNER.md"

@Composable
private fun GuideBlock(block: GuideMarkdown.Block) {
    val body = MaterialTheme.typography.bodyMedium
    when (block) {
        is GuideMarkdown.Block.Heading -> Text(
            inline(block.text),
            style = if (block.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 8.dp).semantics { heading() },
        )
        is GuideMarkdown.Block.Paragraph -> Text(inline(block.text), style = body)
        is GuideMarkdown.Block.ListItem -> Row(Modifier.padding(start = (block.indent * 20).dp)) {
            Text(block.marker, style = body, modifier = Modifier.width(24.dp))
            Text(inline(block.text), style = body)
        }
        is GuideMarkdown.Block.Quote -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Text(inline(block.text), style = body, modifier = Modifier.padding(12.dp))
        }
        is GuideMarkdown.Block.Code -> Text(
            block.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
        // Tables read badly at 320 dp: each row becomes a small card, "Header: value" per cell.
        is GuideMarkdown.Block.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.rows.forEach { row ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEachIndexed { i, cell ->
                            val label = block.header.getOrNull(i)
                            if (i == 0) Text(inline(cell), style = body, fontWeight = FontWeight.Bold)
                            else Text(inline(if (label.isNullOrBlank()) cell else "$label: $cell"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        GuideMarkdown.Block.Rule -> HorizontalDivider()
    }
}

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        GuideMarkdown.spans(text).forEach { s ->
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
            )
            withStyle(style) { append(s.text) }
        }
    }
}
