package one.wabbit.mu.types

import one.wabbit.mu.runtime.MuStdValue
import kotlin.test.*
import kotlin.reflect.typeOf

class MuTypeKTypeSpec {
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `star projection produces Exists around the constructor`() {
        val t = MuType.fromKType(typeOf<List<*>>())
        assertTrue(t is MuType.Exists, "Expected Exists for star-projection, got $t")

        val ex = t as MuType.Exists
        assertEquals(1, ex.vars.size, "Expected exactly one existential")

        val inner = ex.tpe as? MuType.Constructor
            ?: error("Exists should wrap a Constructor, got ${ex.tpe}")
        assertEquals("kotlin.collections.List", inner.head)
        assertTrue(inner.args[0] is MuType.Use, "List arg should be existential use, got ${inner.args[0]}")
    }
}
