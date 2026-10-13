package com.paperview.mobile.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperField
import com.paperview.mobile.ui.components.PaperMasthead
import com.paperview.mobile.ui.components.PaperRow
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme

/**
 * Tools as a set of labelled rows under section rules.
 *
 * A row can carry a real description, which fourteen icon tiles cannot, and it
 * scales if more tools ever arrive.
 */
@Composable
fun ToolsScreen(
    history: List<ToolId>,
    onPick: (Tool) -> Unit,
    onClearHistory: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = PaperTheme.colors
    var query by remember { mutableStateOf("") }
    val groups = remember(query) { ToolCatalog.grouped(query) }
    val recent = remember(history, query) {
        if (query.isNotBlank()) emptyList() else history.map { ToolCatalog.byId(it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            PaperMasthead(
                title = "Tools",
                subtitle = "${ToolCatalog.all.size} tools · everything runs on this device",
            )
            Spacer(Modifier.height(14.dp))
            PaperField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Search tools",
                leadingIcon = Icons.Outlined.Search,
            )
        }

        if (recent.isNotEmpty()) {
            item { SectionHeading("Recently used") }
            items(recent, key = { "recent_${it.id}" }) { tool ->
                PaperRow(
                    title = tool.title,
                    subtitle = tool.subtitle,
                    icon = tool.icon,
                    onClick = { onPick(tool) },
                )
                Hairline(inset = 37)
            }
            item {
                InlineTextButton("Clear", onClick = onClearHistory)
            }
        }

        for ((category, tools) in groups) {
            item(key = "head_${category.name}") { SectionHeading(category.label) }
            items(tools, key = { it.id }) { tool ->
                PaperRow(
                    title = tool.title,
                    subtitle = tool.subtitle,
                    icon = tool.icon,
                    onClick = { onPick(tool) },
                )
                Hairline(inset = 37)
            }
        }

        if (groups.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                    Text(
                        "Nothing matches “$query”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}
