package com.warden.blocker.ui.owner

import com.warden.blocker.ui.owner.GuideMarkdown.Block
import com.warden.blocker.ui.owner.GuideMarkdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S3: the device-owner guide is shown inside the app, from docs/DEVICE_OWNER.md. */
class GuideMarkdownTest {

    @Test fun blocks() {
        val md = """
            # Title

            Some text
            on two lines.

            ## Step 1

            1. **Open** Settings.
            2. Tap seven times
               until it says so.
               - nested
            - plain

            > A quote
            > goes on.

            ```powershell
            adb shell dpm set-device-owner x
            ```

            | Rule | What |
            |---|---|
            | A | does a |

            ---
        """.trimIndent()
        assertEquals(
            listOf(
                Block.Heading(1, "Title"),
                Block.Paragraph("Some text on two lines."),
                Block.Heading(2, "Step 1"),
                Block.ListItem("1.", "**Open** Settings.", 0),
                Block.ListItem("2.", "Tap seven times until it says so.", 0),
                Block.ListItem("•", "nested", 1),
                Block.ListItem("•", "plain", 0),
                Block.Quote("A quote goes on."),
                Block.Code("adb shell dpm set-device-owner x"),
                Block.Table(listOf("Rule", "What"), listOf(listOf("A", "does a"))),
                Block.Rule,
            ),
            GuideMarkdown.parse(md),
        )
    }

    @Test fun inlineMarks() {
        assertEquals(
            listOf(
                Span("Tap "), Span("Build number", bold = true), Span(" in "), Span("Settings", italic = true),
                Span(", run "), Span("adb", code = true), Span(": "), Span("https://x.y"),
            ),
            GuideMarkdown.spans("Tap **Build number** in *Settings*, run `adb`: <https://x.y>"),
        )
        // A lone star stays as it is.
        assertEquals(listOf(Span("7× * tap")), GuideMarkdown.spans("7× * tap"))
    }

    @Test fun theRealGuideParses() {
        val doc = listOf(File("../docs/DEVICE_OWNER.md"), File("docs/DEVICE_OWNER.md")).first { it.isFile }
        val blocks = GuideMarkdown.parse(doc.readText())
        assertTrue(blocks.first() is Block.Heading)
        assertTrue("has the setup command", blocks.any { it is Block.Code && it.text.contains("dpm set-device-owner") })
        assertTrue("has the rules table", blocks.any { it is Block.Table && it.rows.size >= 7 })
        assertTrue("has the way back", blocks.any { it is Block.Heading && it.text.contains("Notausgang") })
    }
}
