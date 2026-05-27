package dev.swiftcrossui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * AndroidComposeBackendActivity
 *
 * The single Activity for apps built on the Compose backend. Declare this in
 * AndroidManifest.xml as your launcher activity.
 *
 * Swift starts the app, then calls ComposeBackendHost APIs to build the widget
 * tree. The activity observes [ComposeBackendHost.nodes] and
 * [ComposeBackendHost.rootNodeId] — both are Compose state, so changes are
 * picked up automatically without any explicit signaling. 
 */
class AndroidComposeBackendActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AndroidComposeBackendRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Let Swift know the activity is going away so it can tear down
        // its own state cleanly.
        AndroidComposeBackendHost.clearAll()
    }
}

/**
 * Root composable. Reads rootNodeId directly from the host object — because
 * rootNodeId is a plain @Volatile var we wrap it in a derivedStateOf so
 * Compose re-reads it when the snapshot changes.
 *
 * In practice, Swift calls setRootNode() once at startup, so this rarely
 * recomposes except during navigation.
 */
@Composable
private fun AndroidComposeBackendRoot() {
    // nodes is a SnapshotStateMap so Compose subscribes automatically.
    val nodes = AndroidComposeBackendHost.nodes

    // rootNodeId is mutated through mainHandler.post{} which runs inside a
    // Compose write transaction, so Compose will see it as state.
    // We expose it as a snapshot-aware integer so the composable recomposes
    // when setRootNode() is called.
    val rootId = AndroidComposeBackendHost.rootNodeId

    Box(modifier = Modifier.fillMaxSize()) {
        if (rootId != -1) {
            RenderNode(nodeId = rootId, nodes = nodes)
        }
    }
}
