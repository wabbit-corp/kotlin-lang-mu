package one.wabbit.mu.types

import kotlin.test.*

/**
 * These tests isolate the "lost representative" bug in MutableEqLattice.join.
 *
 * They specifically create TWO existing clusters, attach a representative to one,
 * then merge the clusters.
 */
class MutableEqLatticeBindingSpec {

    @Test
    fun `binding survives merge of two pre-existing clusters`() {
        val m = MutableEqLattice<String, String>()

        // Cluster #0: {a} with representative "T"
        m["a"] = "T"

        // Cluster #1: {b, c} (created by joining two elements with no representative)
        m.join("b", "c")

        // Sanity: before merge, 'a' yields "T"; 'b' has no binding
        assertEquals("T", m["a"])
        assertNull(m["b"])

        // Now merge the two existing clusters:
        // current implementation links small->large but LOSES the c2r value ("T").
        m.join("a", "b")

        // Expected: the representative "T" is visible through every member
        // of the merged cluster. CURRENTLY FAILS without the join-migration patch.
        assertEquals("T", m["a"])
        assertEquals("T", m["b"])
        assertEquals("T", m["c"])
    }

    @Test
    fun `binding survives transitive merges`() {
        val m = MutableEqLattice<String, String>()
        m["x"] = "R"      // cluster X: {x} with rep "R"
        m.join("a", "b")  // cluster A: {a, b}
        m.join("b", "c")  // still cluster A: {a, b, c}
        m.join("x", "a")  // merge clusters X and A

        // Without migration, the "R" binding disappears after the merge.
        assertEquals("R", m["x"])
        assertEquals("R", m["a"])
        assertEquals("R", m["b"])
        assertEquals("R", m["c"])
    }

    @Test
    fun `binding persists when joining an existing cluster with a fresh element`() {
        val m = MutableEqLattice<String, String>()
        m["a"] = "T"      // cluster {a} with rep "T"
        m.join("a", "b")  // 'b' gets attached to 'a''s cluster (no root merge)

        // This case already works in the old code; serves as a baseline.
        assertEquals("T", m["a"])
        assertEquals("T", m["b"])
    }

    @Test
    fun `when both sides have different representatives, one must survive merge`() {
        val m = MutableEqLattice<String, String>()
        m["a"] = "TA"     // cluster A: {a} with "TA"
        m["b"] = "TB"     // cluster B: {b} with "TB"
        m.join("a", "b")  // merge A and B

        // Pre-fix, both become null (binding lost).
        // Post-fix, we keep the "winner" root's representative; exact choice is implementation-defined.
        val va = m["a"]
        val vb = m["b"]
        assertNotNull(va, "expected one representative to survive merge")
        assertEquals(va, vb, "all members must see the same representative")
        assertTrue(va == "TA" || va == "TB", "representative must be one of the pre-merge values")
    }
}
