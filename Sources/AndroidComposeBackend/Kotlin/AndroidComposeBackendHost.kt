package dev.swiftcrossui.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AndroidComposeBackendHost
 *
 * The single object Swift talks to via JNI/swift-java.
 *
 * Threading contract
 * ------------------
 * All writes to [nodes] MUST happen on the main thread — Compose's snapshot
 * system is not thread-safe for writes. We enforce this with [mainHandler].
 *
 * Swift calls come in on whatever thread the Swift run loop uses, so every
 * @JvmStatic entry point posts to main before touching [nodes].
 *
 * Events go the other direction: Compose pushes to [eventQueue] (thread-safe),
 * and Swift drains it from its own polling loop.
 */
object AndroidComposeBackendHost {

    val nodes: SnapshotStateMap<Int, WidgetNode> = mutableStateMapOf()

    /** The ID of the top-level node Swift wants rendered. -1 = nothing yet. */
    @Volatile
    var rootNodeId: Int = -1
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Create a new node with the given type. Must be followed by property/child
     * calls before [setRootNode] makes it visible.
     */
    @JvmStatic
    fun createNode(id: Int, type: String) {
        onMain { nodes[id] = WidgetNode(id = id, type = type) }
    }

    /**
     * Set or overwrite a single string property on an existing node.
     * Compose sees this as a new WidgetNode value and recomposes affected subtrees.
     */
    @JvmStatic
    fun setProperty(id: Int, key: String, value: String) {
        onMain {
            val existing = nodes[id] ?: return@onMain
            nodes[id] = existing.copy(
                properties = existing.properties + (key to value)
            )
        }
    }

    /**
     * Replace the entire ordered child list of a container.
     * Swift calls this when the child count changes.
     */
    @JvmStatic
    fun setChildren(parentId: Int, childIds: IntArray) {
        onMain {
            val existing = nodes[parentId] ?: return@onMain
            nodes[parentId] = existing.copy(children = childIds.toList())
        }
    }

    /**
     * Set the absolute position of the child at [index] inside [containerId].
     * Called by SCUI after it finishes computing layout for a container.
     * [x] and [y] are in dp.
     */
    @JvmStatic
    fun setPosition(containerId: Int, index: Int, x: Int, y: Int) {
        onMain {
            val existing = nodes[containerId] ?: return@onMain
            nodes[containerId] = existing.copy(
                childPositions = existing.childPositions + (index to Pair(x, y))
            )
        }
    }

    /**
     * Declare which node is the root. Triggers a full re-render from that node.
     * Safe to call multiple times (e.g. after a navigation push).
     */
    @JvmStatic
    fun setRootNode(id: Int) {
        onMain { rootNodeId = id }
    }

    /**
     * Remove a node from the tree. Swift is responsible for removing it from
     * its parent's child list first to avoid dangling references.
     */
    @JvmStatic
    fun removeNode(id: Int) {
        onMain { nodes.remove(id) }
    }

    /**
     * Wipe everything. Call before tearing down the activity or during hot-reload.
     */
    @JvmStatic
    fun clearAll() {
        onMain {
            nodes.clear()
            rootNodeId = -1
        }
    }

    /**
     * Thread-safe event queue. Each entry is a JSON string:
     *   {"id": 42, "type": "click", "payload": ""}
     *   {"id": 7,  "type": "change", "payload": "hello"}
     *   {"id": 3,  "type": "toggle", "payload": "true"}
     *   {"id": 9,  "type": "slide",  "payload": "0.75"}
     */
    private val eventQueue = ConcurrentLinkedQueue<String>()

    /**
     * Dequeue one event and return it as a JSON string, or null if the queue is
     * empty. Swift calls this in a tight loop on a background thread.
     */
    @JvmStatic
    fun pollEvent(): String? = eventQueue.poll()

    /**
     * How many events are waiting. Swift can use this to decide whether to spin
     * or yield.
     */
    @JvmStatic
    fun pendingEventCount(): Int = eventQueue.size

    internal fun pushEvent(widgetId: Int, eventType: String, payload: String = "") {
        // Escape payload for JSON — replace backslash then double-quote
        val safe = payload.replace("\\", "\\\\").replace("\"", "\\\"")
        eventQueue.offer("""{"id":$widgetId,"type":"$eventType","payload":"$safe"}""")
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
