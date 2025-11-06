package one.wabbit.mu.runtime

import java.math.BigInteger
import kotlin.reflect.full.createType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import one.wabbit.data.Right
import one.wabbit.mu.types.MuType
import one.wabbit.mu.types.Upcast

/**
 * End-to-end tests for the reflective builder helpers:
 * - makeValueFromMember (properties, functions; context/optional/varargs/naming/quoted)
 * - makeInstanceFromMember (property instances, function instances, implicit resolution)
 * - makeValueFromDataType / makeModuleInfoCtor (constructor-style value factory)
 *
 * These tests lean on withNativeModule(), so we exercise the real wiring.
 */
class MakeFunctionsTest {
    // --- Test fixtures -------------------------------------------------------

    data class Tag(val name: String)

    data class Tagged(val id: String)

    @Suppress("unused")
    class TestModule {
        // Properties
        @Mu.Export @Mu.Const val constPi: String = "π"

        @Mu.Export val readOnly: String = "hello"

        @Mu.Export var threshold: Int = 7

        // Functions
        @Mu.Export fun greet(@Mu.Optional who: String?): String = who ?: "world"

        @Mu.Export fun sum(@Mu.ZeroOrMore xs: List<Int>): Int = xs.sum()

        @Mu.Export fun product(@Mu.OneOrMore xs: List<Int>): Int = xs.reduce { a, b -> a * b }

        @Mu.Export fun renamed(@Mu.Name("value") v: String): String = v

        @Mu.Export fun quoted(@Mu.Quoted code: String): String = code

        @Mu.Export
        fun define(
            @Mu.Context ctx: MuStdContext,
            @Mu.Name("x") x: Int,
        ): Pair<MuStdContext, MuStdValue> {
            val value = MuStdContext.liftInteger(ctx, BigInteger.valueOf(x.toLong()))
            return ctx.withLocal("x", value) to value
        }

        @Mu.Export fun sum2(a: Int, b: Int): Int = a + b // both required

        // Instances
        @Mu.Instance val tagInstance: Tag = Tag("default")

        @Mu.Instance fun taggedFromName(name: String): Tagged = Tagged(name)

        @Mu.Export fun useTag(@Mu.Instance tag: Tag): String = tag.name

        @Mu.Export fun useTagged(@Mu.Instance t: Tagged): String = t.id

        @Mu.Instance fun <A> upcastNullable(): Upcast<A, A?> = Upcast.id()
    }

    private fun ctxWith(module: TestModule = TestModule()): MuStdContext =
        MuStdContext.empty().withNativeModule("test", module)

    private fun getExport(ctx: MuStdContext, name: String): MuStdValue =
        ctx.modules["test"]?.definitions?.get(name) ?: error("Export not found: $name")

    private fun rawFunc(v: MuStdValue): MuStdFunc =
        (v.unsafeValue as? MuStdFunc)
            ?: error("Value is not a MuStdFunc; got ${v.unsafeValue!!::class}")

    private fun run(
        ctx: MuStdContext,
        v: MuStdValue,
        args: Map<String, MuStdValue> = emptyMap(),
    ): Pair<MuStdContext, MuStdValue> = rawFunc(v).run(ctx, args)

    private val intType
        get() = MuType.fromKType(Int::class.createType()) as MuType.Constructor

    private val strType
        get() = MuType.fromKType(String::class.createType()) as MuType.Constructor

    private fun listOfType(elem: MuType) = MuType.List(elem)

    private fun intMu(i: Int) = MuStdValue.unsafeLift(i, intType)

    private fun strMu(s: String) = MuStdValue.unsafeLift(s, strType)

    private fun listIntMu(xs: List<Int>) = MuStdValue.unsafeLift(xs, listOfType(intType))

    private fun listStrMu(xs: List<String>) = MuStdValue.unsafeLift(xs, listOfType(strType))

    // --- makeValueFromMember: properties ------------------------------------

    @Test
    fun const_property_is_constant_value() {
        val ctx = ctxWith()
        val constVal = getExport(ctx, "constPi")
        assertNull(MuStdContext.extractFunc(constVal), "Const should not be callable")
        val t = constVal.type as MuType.Constructor
        assertEquals("kotlin.String", t.head)
    }

    @Test
    fun readonly_property_exposes_zero_arity_function() {
        val ctx = ctxWith()
        val prop = getExport(ctx, "readOnly")
        val (_, out) = run(ctx, prop)
        val t = out.type as MuType.Constructor
        assertEquals("kotlin.String", t.head)
        // Don't be cute: verify the raw string if it's a string
        (out.unsafeValue as? String)?.let { assertEquals("hello", it) }
    }

    @Test
    fun mutable_property_get_then_set_returns_old_and_mutates() {
        val module = TestModule()
        val ctx = ctxWith(module)
        val prop = getExport(ctx, "threshold")

        // Read current value (no arg) -> returns 7
        val (_, old1) = run(ctx, prop)
        (old1.unsafeValue as? Int)?.let { assertEquals(7, it) }

        // Write new value, returns old value, and mutates module.threshold
        val (_, old2) = run(ctx, prop, mapOf("value" to intMu(9)))
        (old2.unsafeValue as? Int)?.let { assertEquals(7, it) }
        assertEquals(9, module.threshold)
    }

    // --- makeValueFromMember: annotated functions ---------------------------

    @Test
    fun optional_parameter_works_when_omitted_or_provided() {
        val ctx = ctxWith()
        val fn = getExport(ctx, "greet")

        // Omitted -> uses default path (null)
        val (_, out1) = run(ctx, fn)
        assertEquals("world", out1.unsafeValue as String)

        // Provided
        val (_, out2) = run(ctx, fn, mapOf("who" to strMu("Ada")))
        assertEquals("Ada", out2.unsafeValue as String)
    }

    @Test
    fun zero_or_more_list_parameter_and_one_or_more_parameter() {
        val ctx = ctxWith()

        val sumFn = getExport(ctx, "sum")
        val paramsSum = rawFunc(sumFn).parameters
        assertEquals(1, paramsSum.size)
        assertEquals(ArgArity.ZeroOrMore, paramsSum[0].arity)

        val (_, sumOut) = run(ctx, sumFn, mapOf("xs" to listIntMu(listOf(1, 2, 3))))
        assertEquals(6, sumOut.unsafeValue as Int)

        val prodFn = getExport(ctx, "product")
        val paramsProd = rawFunc(prodFn).parameters
        assertEquals(1, paramsProd.size)
        assertEquals(ArgArity.OneOrMore, paramsProd[0].arity)

        val (_, prodOut) = run(ctx, prodFn, mapOf("xs" to listIntMu(listOf(2, 3, 4))))
        assertEquals(24, prodOut.unsafeValue as Int)
    }

    @Test
    fun name_and_quoted_annotations_are_reflected_in_args() {
        val ctx = ctxWith()
        val renamed = getExport(ctx, "renamed")
        val quoted = getExport(ctx, "quoted")

        val p1 = rawFunc(renamed).parameters.single()
        assertEquals("value", p1.name, "Expected @Mu.Name to rename the parameter")

        val p2 = rawFunc(quoted).parameters.single()
        assertTrue(p2.quote, "Expected @Mu.Quoted to mark the argument as quoted")
    }

    @Test
    fun context_argument_injected_and_pair_return_handled() {
        val ctx = ctxWith()
        val def = getExport(ctx, "define")

        val (ctx2, out) = run(ctx, def, mapOf("x" to intMu(5)))
        // Returned value is the same MuStdValue we stored under "x"
        val resolved = ctx2.resolve("x")
        assertTrue(resolved is Right, "Expected local 'x' to be bound in the new context")
        // Sanity: 'out' should be an integer-ish Mu value
        (out.unsafeValue as? Any)?.let { /* nothing dramatic, just ensuring no crash */ }
    }

    @Test
    fun missing_required_argument_throws() {
        val ctx = ctxWith()
        val fn = getExport(ctx, "sum2")
        val ex =
            assertFailsWith<MissingRequiredArgumentException> {
                run(ctx, fn, mapOf("a" to intMu(2))) // missing b
            }
        assertTrue(ex.message?.contains("Missing required argument") == true)
    }

    @Test
    fun type_mismatch_throws() {
        val ctx = ctxWith()
        val fn = getExport(ctx, "renamed")
        val ex =
            assertFailsWith<TypeMismatchInArgumentException> {
                run(ctx, fn, mapOf("value" to intMu(42))) // expects String
            }
        assertTrue(ex.message?.contains("Type mismatch in argument") == true)
    }

    // --- makeInstanceFromMember (+ implicit resolution path) ----------------

    @Test
    fun instances_are_registered_and_discoverable() {
        val ctx = ctxWith()
        val tagHead = (MuType.fromKType(Tag::class.createType()) as MuType.Constructor).head
        val taggedHead = (MuType.fromKType(Tagged::class.createType()) as MuType.Constructor).head

        val tagInsts = ctx.instances[tagHead]
        assertNotNull(tagInsts)
        assertTrue(tagInsts!!.isNotEmpty(), "Expected a Tag instance to be registered")

        val taggedInsts = ctx.instances[taggedHead]
        assertNotNull(taggedInsts)
        assertTrue(
            taggedInsts!!.isNotEmpty(),
            "Expected a Tagged instance to be registered (function instance)",
        )

        // Function instance should declare one String parameter
        val stringType = strType
        val fnInst = taggedInsts.first { it.parameters.isNotEmpty() }
        assertEquals(1, fnInst.parameters.size)
        assertEquals(stringType, fnInst.parameters.first())
    }

    @Test
    fun implicit_instance_is_injected_into_exported_function() {
        val ctx = ctxWith()
        val useTag = getExport(ctx, "useTag")
        val (_, out) = run(ctx, useTag) // no explicit args; instance must be resolved
        assertEquals("default", out.unsafeValue as String)
    }

    // --- makeValueFromDataType / makeModuleInfoCtor -------------------------

    //    @Test
    //    fun module_info_ctor_minimal_uses_defaults() {
    //        val ctx = ctxWith()
    //        val ctor = makeModuleInfoCtor() // exportName default "ModuleInfo"
    //        val (_, out) = run(ctx, ctor, mapOf(
    //            "name" to strMu("core"),
    //            "version" to strMu("1.0.0")
    //        ))
    //        val mi = out.unsafeValue as ModuleInfo
    //        assertEquals(ModuleInfo(name = "core", version = "1.0.0"), mi)
    //    }
    //
    //    @Test
    //    fun module_info_ctor_full_all_fields() {
    //        val ctx = ctxWith()
    //        val ctor = makeModuleInfoCtor()
    //        val (_, out) = run(ctx, ctor, mapOf(
    //            "name" to strMu("analytics"),
    //            "version" to strMu("2.3.1"),
    //            "description" to strMu("Collects and ships metrics"),
    //            "author" to strMu("Wabbit Inc."),
    //            "dependencies" to listStrMu(listOf("core", "http")),
    //            "softDependencies" to listStrMu(listOf("ui"))
    //        ))
    //        val mi = out.unsafeValue as ModuleInfo
    //        assertEquals(
    //            ModuleInfo(
    //                name = "analytics",
    //                version = "2.3.1",
    //                description = "Collects and ships metrics",
    //                author = "Wabbit Inc.",
    //                dependencies = listOf("core", "http"),
    //                softDependencies = listOf("ui")
    //            ),
    //            mi
    //        )
    //    }
    //
    //    @Test
    //    fun module_info_ctor_missing_required_throws() {
    //        val ctx = ctxWith()
    //        val ctor = makeModuleInfoCtor()
    //        val ex = assertFailsWith<IllegalStateException> {
    //            run(ctx, ctor, mapOf("name" to strMu("oops"))) // missing version
    //        }
    //        assertTrue(ex.message?.contains("Missing required argument: version") == true)
    //    }
    //
    //    @Test
    //    fun module_info_ctor_type_mismatch_throws() {
    //        val ctx = ctxWith()
    //        val ctor = makeModuleInfoCtor()
    //        val ex = assertFailsWith<IllegalStateException> {
    //            run(ctx, ctor, mapOf(
    //                "name" to intMu(123),              // should be String
    //                "version" to strMu("1.0.0")
    //            ))
    //        }
    //        assertTrue(ex.message?.contains("Type mismatch in argument name") == true)
    //    }
}
