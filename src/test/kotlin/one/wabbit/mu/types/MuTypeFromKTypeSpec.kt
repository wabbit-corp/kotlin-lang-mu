package one.wabbit.mu.types

import kotlin.test.*
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf


class MuTypeFromKTypeSpec {

    // Simple fixture types for nested/inner/companion cases
    class Enclosing {
        class Nested
        inner class Inner
        companion object
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `nested, inner, and companion have stable qualified names`() {
        // Nested
        val tNested = MuType.fromKType(typeOf<Enclosing.Nested>())
        val cNested = tNested as? MuType.Constructor ?: error("Expected Constructor, got $tNested")
        assertEquals(Enclosing.Nested::class.qualifiedName!!, cNested.head)
        assertTrue(cNested.args.isEmpty())

        // Inner
        val tInner = MuType.fromKType(typeOf<Enclosing.Inner>())
        val cInner = tInner as? MuType.Constructor ?: error("Expected Constructor, got $tInner")
        assertEquals(Enclosing.Inner::class.qualifiedName!!, cInner.head)
        assertTrue(cInner.args.isEmpty())

        // Companion object
        val tComp = MuType.fromKType(Enclosing.Companion::class.createType())
        val cComp = tComp as? MuType.Constructor ?: error("Expected Constructor, got $tComp")
        assertEquals(Enclosing.Companion::class.qualifiedName!!, cComp.head)
        assertTrue(cComp.args.isEmpty())
    }

    @Test
    fun `local classes are currently unsupported (qualifiedName == null)`() {
        class Local
        val kt = Local::class.createType()
        val mt = MuType.fromKType(kt)
        assertTrue(mt is MuType.Constructor)
        assertTrue(mt.head.startsWith("${MuTypeFromKTypeSpec::class.qualifiedName!!}\$"))
        assertTrue(mt.head.endsWith("\$Local"))
        assertTrue(mt.args.isEmpty())
    }

    @Test
    fun `anonymous object types are currently unsupported (qualifiedName == null)`() {
        val anon = object { val x = 1 }
        val kt = anon::class.createType()
        val mt = MuType.fromKType(kt)

        assertTrue(mt is MuType.Constructor)
        assertTrue(mt.head.startsWith("${MuTypeFromKTypeSpec::class.qualifiedName!!}\$"))
        assertTrue(mt.args.isEmpty())
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `function types map to kotlin_FunctionN constructors (not MuType_Func)`() {
        val t = MuType.fromKType(typeOf<(Int) -> String>())
        val c = t as? MuType.Constructor ?: error("Expected Constructor, got $t")

        // Most JVMs reflect function types as kotlin.Function1
        // (suspend variants may reflect as kotlin.coroutines.SuspendFunction1)
        assertTrue(
            c.head.endsWith("Function1") || c.head.endsWith("SuspendFunction1"),
            "Expected Function1-ish head, got ${c.head}"
        )

        // Args: [ParamType, ReturnType]
        assertEquals(2, c.args.size)
        assertEquals(MuType.Int, c.args[0])
        assertEquals(MuType.String, c.args[1])
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `nullable return type vs nullable function type`() {
        // Return type nullable
        val t1 = MuType.fromKType(typeOf<(Int) -> String?>())
        val c1 = t1 as MuType.Constructor
        val ret1 = c1.args[1]
        val n1 = ret1 as? MuType.Constructor ?: error("Expected return to be Constructor, got $ret1")
        assertEquals("?", n1.head)
        assertEquals(MuType.String, n1.args.single())

        // Function type itself nullable
        val t2 = MuType.fromKType(typeOf<((Int) -> String)?>())
        val n2 = t2 as? MuType.Constructor ?: error("Expected Constructor, got $t2")
        assertEquals("?", n2.head)
        val inner = n2.args.single() as? MuType.Constructor ?: error("Expected inner Constructor, got ${n2.args.single()}")
        assertTrue(inner.head.endsWith("Function1") || inner.head.endsWith("SuspendFunction1"))
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `star projections produce Exists at each level (Map star, List star)`() {
        val t = MuType.fromKType(typeOf<Map<*, List<*>>>())

        val topExists = t as? MuType.Exists ?: error("Expected top-level Exists, got $t")
        assertEquals(1, topExists.vars.size)

        val mapC = topExists.tpe as? MuType.Constructor
            ?: error("Expected Constructor under Exists, got ${topExists.tpe}")
        assertEquals("kotlin.collections.Map", mapC.head)

        // key: Use(existential)
        assertTrue(mapC.args[0] is MuType.Use, "Expected existential key, got ${mapC.args[0]}")

        // value: Exists(var).Constructor(List, [Use(var)])
        val valueExists = mapC.args[1] as? MuType.Exists
            ?: error("Expected Exists for List<*>, got ${mapC.args[1]}")
        assertEquals(1, valueExists.vars.size)
        val listC = valueExists.tpe as? MuType.Constructor
            ?: error("Expected Constructor under inner Exists, got ${valueExists.tpe}")
        assertEquals("kotlin.collections.List", listC.head)
        assertTrue(listC.args[0] is MuType.Use)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `Array star projection becomes Exists around Array`() {
        val t = MuType.fromKType(typeOf<Array<*>>())
        val ex = t as? MuType.Exists ?: error("Expected Exists, got $t")
        assertEquals(1, ex.vars.size)
        val arr = ex.tpe as? MuType.Constructor ?: error("Expected Constructor under Exists, got ${ex.tpe}")
        assertEquals("kotlin.Array", arr.head)
        assertTrue(arr.args[0] is MuType.Use)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `variance is ignored in fromKType (documenting current behavior)`() {
        // Declaration-site: List<out E>, use-site 'out Number' is allowed and becomes just Number.
        val tOut = MuType.fromKType(typeOf<List<out Number>>())
        val cOut = tOut as MuType.Constructor
        assertEquals("kotlin.collections.List", cOut.head)
        val argOut = cOut.args.single() as MuType.Constructor
        assertEquals("kotlin.Number", argOut.head)

        // MutableList<E> is invariant; use-site 'in Number' is allowed,
        // but fromKType ignores the 'in' and records just Number.
        val tIn = MuType.fromKType(typeOf<MutableList<in Number>>())
        val cIn = tIn as MuType.Constructor
        assertEquals("kotlin.collections.MutableList", cIn.head)
        val argIn = cIn.args.single() as MuType.Constructor
        assertEquals("kotlin.Number", argIn.head)
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `Nothing nullable becomes ?(Nothing)`() {
        val t = MuType.fromKType(typeOf<Nothing?>())
        val n = t as? MuType.Constructor ?: error("Expected Constructor, got $t")
        assertEquals("?", n.head)
        assertEquals(MuType.Nothing, n.args.single())
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun `kotlin Nothing nullable normalizes, but java Void nullable stays Void`() {
        // Kotlin Nothing?
        val kn = MuType.fromKType(typeOf<Nothing?>())
        val n = kn as MuType.Constructor
        assertEquals("?", n.head)
        val innerN = n.args.single() as MuType.Constructor
        assertEquals("kotlin.Nothing", innerN.head)

        // Genuine Java Void?
        val jv = MuType.fromKType(typeOf<java.lang.Void?>())
        val v = jv as MuType.Constructor
        assertEquals("?", v.head)
        val innerV = v.args.single() as MuType.Constructor
        assertEquals("java.lang.Void", innerV.head)
    }
}
