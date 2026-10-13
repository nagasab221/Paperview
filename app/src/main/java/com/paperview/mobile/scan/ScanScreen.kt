package com.paperview.mobile.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paperview.mobile.ui.components.PaperButton
import com.paperview.mobile.ui.components.PaperMasthead
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme

/** The Scan tab. ML Kit owns the capture flow, so this is framing and one action. */
@Composable
fun ScanScreen(
    onStartScan: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = PaperTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
    ) {
        PaperMasthead(title = "Scan", subtitle = "Camera to PDF")

        Spacer(Modifier.height(24.dp))

        // A drawn page rather than an icon — the same paper motif as the covers.
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(colors.surface)
                .border(1.dp, colors.hairline),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(96.dp)
                        .height(124.dp)
                        .background(colors.background)
                        .border(1.dp, colors.hairline),
                    contentAlignment = Alignment.Center,
                ) {
                    Column {
                        repeat(4) {
                            Box(
                                Modifier
                                    .padding(bottom = 7.dp)
                                    .width(if (it == 3) 30.dp else 52.dp)
                                    .height(2.dp)
                                    .background(colors.hairline),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Point the camera at a page.",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Edges are found automatically and the perspective corrected. Then the " +
                "lighting is flattened, so the paper comes out white instead of grey " +
                "and the shadow of your hand disappears. Capture several pages and " +
                "they become one document you can read, sign or upload.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )

        Spacer(Modifier.height(24.dp))
        PaperButton("Start scanning", onStartScan)

        SectionHeading("Good to know")
        Spacer(Modifier.height(12.dp))
        listOf(
            "Choose how the page is cleaned up before saving — in colour, grey, or " +
                "pure black and white for printing.",
            "Scans are saved as PDFs and land in your Library like any other document.",
            "Nothing is uploaded — the whole capture happens on the device.",
            "Use Image to PDF instead if the photos are already in your gallery.",
        ).forEach { line ->
            Text(
                "— $line",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}
