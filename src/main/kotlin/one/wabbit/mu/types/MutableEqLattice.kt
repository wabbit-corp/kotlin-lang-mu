package one.wabbit.mu.types

class MutableEqLattice<From, To> private constructor(
    private val e2c: MutableMap<From, Int>,
    private val c2c: MutableMap<Int, Int>,
    private val c2r: MutableMap<Int, To>,
) {
    constructor() : this(mutableMapOf(), mutableMapOf(), mutableMapOf())
    constructor(vararg lists: List<From>) : this() {
        for (list in lists) {
            for (i in 1 until list.size) {
                join(list[i - 1], list[i])
            }
        }
    }

    fun copy(): MutableEqLattice<From, To> = MutableEqLattice(
        e2c.toMutableMap(),
        c2c.toMutableMap(),
        c2r.toMutableMap()
    )

    private fun getCluster(e: From): Int? {
        val r0 = e2c[e] ?: return null
        var r = r0
        while (true) {
            r = c2c[r] ?: break
        }
        if (r != r0) {
            e2c[e] = r
        }
        return r
    }

    private fun getOrUpdateCluster(e: From): Int {
        val c = getCluster(e)
        if (c != null) return c
        val nextCluster = e2c.size
        e2c[e] = nextCluster
        return nextCluster
    }

    operator fun set(e: From, r: To) {
        val c = getOrUpdateCluster(e)
        c2r[c] = r
    }

    operator fun get(e: From): To? {
        val c = getCluster(e) ?: return null
        return c2r[c]
    }

    fun join(first: From, second: From) {
        // require(first !== second) { "Cannot join the same element" }
        // require(first != second) { "Cannot join equal elements" }

        val c1 = getCluster(first)
        val c2 = getCluster(second)

        if (c1 == null && c2 == null) {
            val nextCluster = e2c.size
            e2c[first] = nextCluster
            e2c[second] = nextCluster
        } else if (c1 == null && c2 != null) {
            e2c[first] = c2
        } else if (c1 != null && c2 == null) {
            e2c[second] = c1
        } else {
            c1!!
            c2!!
            if (c1 == c2) {
                return
            } else {
                val (c1, c2) = (minOf(c1, c2) to maxOf(c1, c2))
                c2c[c1] = c2
            }
        }
    }

    fun compare(left: From, right: From): Boolean {
        if (left === right) return true
        if (left == right) return true
        val c1 = getCluster(left)
        val c2 = getCluster(right)
        if (c1 == null) return false
        if (c2 == null) return false
        return c1 == c2
    }

    fun toList(): List<Pair<List<From>, To?>> {
        val clusters = mutableMapOf<Int, MutableList<From>>()
        val representatives = mutableMapOf<Int, To>()
        var nextClusterId = e2c.size
        for ((e, c) in e2c) {
            val cluster = getCluster(e) ?: (nextClusterId++)
            val r = c2r[cluster]
            clusters.getOrPut(cluster) { mutableListOf() }.add(e)
            if (r != null) representatives[cluster] = r
        }
        return clusters.map { (c, es) ->
            es to representatives[c]
        }
    }

    override fun toString(): String {
        return toList().joinToString("; ", "EqMap(", ")") {
            val (es, r) = it
            val esStr = es.joinToString(", ", "[", "]")
            val rStr = r?.toString() ?: "null"
            "$esStr -> $rStr"
        }
    }
}
