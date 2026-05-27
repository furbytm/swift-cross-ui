package dev.swiftcrossui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Recursively renders a WidgetNode tree.
 *
 * Because [nodes] is a SnapshotStateMap, Compose tracks which nodes each
 * call site reads. When Swift mutates a node via [ComposeBackendHost], only
 * the composables that read that specific node recompose — not the whole tree.
 */
@Composable
fun RenderNode(nodeId: Int, nodes: Map<Int, WidgetNode>) {
    val node = nodes[nodeId] ?: return

    when (node.type) {

        WidgetType.CONTAINER -> {
            Box {
                node.children.forEachIndexed { index, childId ->
                    val (x, y) = node.childPositions[index] ?: (0 to 0)
                    Box(modifier = Modifier.offset(x.dp, y.dp)) {
                        RenderNode(childId, nodes)
                    }
                }
            }
        }

        WidgetType.TEXT -> {
            val text = node.prop(PropKey.TEXT) ?: ""
            val fontSize = node.prop(PropKey.FONT_SIZE)?.toIntOrNull()
            val fontWeight = when (node.prop(PropKey.FONT_WEIGHT)) {
                "bold"   -> FontWeight.Bold
                "light"  -> FontWeight.Light
                "medium" -> FontWeight.Medium
                else     -> FontWeight.Normal
            }
            val color = node.prop(PropKey.FOREGROUND)?.let { parseColor(it) }

            Text(
                text = text,
                fontSize = fontSize?.sp ?: LocalTextStyle.current.fontSize,
                fontWeight = fontWeight,
                color = color ?: Color.Unspecified,
            )
        }

        WidgetType.BUTTON -> {
            val label = node.prop(PropKey.LABEL) ?: ""
            val enabled = node.prop(PropKey.ENABLED) != "false"
            Button(
                enabled = enabled,
                onClick = { ComposeBackendHost.pushEvent(node.id, "click") },
            ) {
                Text(label)
            }
        }

        WidgetType.TEXT_FIELD -> {
            // Local draft state so the field feels responsive while Swift processes
            // the change event asynchronously.
            var draft by remember(nodeId) {
                mutableStateOf(node.prop(PropKey.VALUE) ?: "")
            }
            // Keep in sync if Swift pushes an update externally (e.g. clear field)
            val canonical = node.prop(PropKey.VALUE) ?: ""
            LaunchedEffect(canonical) {
                if (draft != canonical) draft = canonical
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { new ->
                    draft = new
                    ComposeBackendHost.pushEvent(node.id, "change", new)
                },
                label = {
                    node.prop(PropKey.PLACEHOLDER)?.let { Text(it) }
                },
                enabled = node.prop(PropKey.ENABLED) != "false",
            )
        }

        WidgetType.TOGGLE -> {
            var checked by remember(nodeId) {
                mutableStateOf(node.prop(PropKey.VALUE) == "true")
            }
            val canonical = node.prop(PropKey.VALUE) == "true"
            LaunchedEffect(canonical) {
                if (checked != canonical) checked = canonical
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(node.prop(PropKey.LABEL) ?: "")
                Switch(
                    checked = checked,
                    onCheckedChange = { new ->
                        checked = new
                        ComposeBackendHost.pushEvent(node.id, "toggle", new.toString())
                    },
                    enabled = node.prop(PropKey.ENABLED) != "false",
                )
            }
        }

        WidgetType.SLIDER -> {
            var value by remember(nodeId) {
                mutableStateOf(node.prop(PropKey.VALUE)?.toFloatOrNull() ?: 0f)
            }
            val min = node.prop(PropKey.MIN_VALUE)?.toFloatOrNull() ?: 0f
            val max = node.prop(PropKey.MAX_VALUE)?.toFloatOrNull() ?: 1f

            Slider(
                value = value,
                valueRange = min..max,
                onValueChange = { new ->
                    value = new
                    ComposeBackendHost.pushEvent(node.id, "slide", new.toString())
                },
                onValueChangeFinished = {
                    ComposeBackendHost.pushEvent(node.id, "slideEnd", value.toString())
                },
            )
        }

        // Unknown type — render children anyway
        else -> Box {
            node.children.forEachIndexed { index, childId ->
                val (x, y) = node.childPositions[index] ?: (0 to 0)
                Box(modifier = Modifier.offset(x.dp, y.dp)) {
                    RenderNode(childId, nodes)
                }
            }
        }
    }
}

/** Shorthand to avoid nullable map lookups everywhere. */
private fun WidgetNode.prop(key: String): String? = properties[key]

/**
 * Parse a hex color string like "#RRGGBB" or "#AARRGGBB".
 * Returns null for anything it can't parse so callers fall back to defaults.
 */
private fun parseColor(hex: String): Color? = runCatching {
    val clean = hex.trimStart('#')
    when (clean.length) {
        6 -> Color(("FF$clean").toLong(16).toInt())
        8 -> Color(clean.toLong(16).toInt())
        else -> null
    }
}.getOrNull()
