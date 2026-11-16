package cz.fenixen.navigation.core.graph

/**
 * Binary min-heap over node ids with **decrease-key** (positions are tracked per node).
 *
 * java.util.PriorityQueue has no decrease-key, so typical code pushes duplicates
 * ("lazy deletion"). That works but bloats the heap; with positions we update in place.
 *
 * Epoch stamping makes [clear] O(1): instead of resetting `pos` for all n nodes before every
 * query we bump an epoch counter – crucial for CH, whose queries touch only ~hundreds of nodes.
 */
internal class IndexedMinHeap(capacity: Int) {
    private val heap = IntArray(capacity)
    private val keys = IntArray(capacity)
    private val pos = IntArray(capacity)
    private val stamp = IntArray(capacity)
    private var epoch = 1
    var size = 0
        private set

    fun isEmpty() = size == 0

    fun clear() {
        size = 0
        epoch++
    }

    fun contains(v: Int) = stamp[v] == epoch && pos[v] >= 0

    fun minKey(): Int = keys[heap[0]]

    fun minNode(): Int = heap[0]

    /** Inserts [v] or changes its key (both decrease and increase are supported). */
    fun update(v: Int, key: Int) {
        if (contains(v)) {
            val old = keys[v]
            keys[v] = key
            if (key < old) siftUp(pos[v]) else siftDown(pos[v])
        } else {
            stamp[v] = epoch
            keys[v] = key
            heap[size] = v
            pos[v] = size
            size++
            siftUp(size - 1)
        }
    }

    fun popMin(): Int {
        val top = heap[0]
        size--
        pos[top] = -1
        if (size > 0) {
            val last = heap[size]
            heap[0] = last
            pos[last] = 0
            siftDown(0)
        }
        return top
    }

    private fun siftUp(start: Int) {
        var i = start
        val v = heap[i]
        val k = keys[v]
        while (i > 0) {
            val parent = (i - 1) ushr 1
            val p = heap[parent]
            if (keys[p] <= k) break
            heap[i] = p
            pos[p] = i
            i = parent
        }
        heap[i] = v
        pos[v] = i
    }

    private fun siftDown(start: Int) {
        var i = start
        val v = heap[i]
        val k = keys[v]
        while (true) {
            val left = 2 * i + 1
            if (left >= size) break
            val right = left + 1
            var child = left
            if (right < size && keys[heap[right]] < keys[heap[left]]) child = right
            val c = heap[child]
            if (keys[c] >= k) break
            heap[i] = c
            pos[c] = i
            i = child
        }
        heap[i] = v
        pos[v] = i
    }
}

/** Per-query state (tentative distances, parents) with O(1) reset via epoch stamping. */
internal class SearchSpace(n: Int) {
    private val dist = IntArray(n)
    private val parent = IntArray(n)
    private val parentEdge = IntArray(n)
    private val stamp = IntArray(n)
    private var epoch = 1
    val heap = IndexedMinHeap(n)
    var settled = 0

    fun reset() {
        epoch++
        heap.clear()
        settled = 0
    }

    fun dist(v: Int): Int = if (stamp[v] == epoch) dist[v] else INF

    fun parent(v: Int): Int = if (stamp[v] == epoch) parent[v] else -1

    fun parentEdge(v: Int): Int = if (stamp[v] == epoch) parentEdge[v] else -1

    fun set(v: Int, d: Int, p: Int, edge: Int = -1) {
        stamp[v] = epoch
        dist[v] = d
        parent[v] = p
        parentEdge[v] = edge
    }

    /** Walks parent pointers from [v] back to the search root. */
    fun pathTo(v: Int): IntArray {
        val out = ArrayList<Int>()
        var cur = v
        while (cur != -1) {
            out += cur
            cur = parent(cur)
        }
        return out.asReversed().toIntArray()
    }
}
