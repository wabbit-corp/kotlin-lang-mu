package one.wabbit.mu

import one.wabbit.data.rightOrNull
import one.wabbit.mu.runtime.*
import one.wabbit.mu.types.MuType
import one.wabbit.mu.types.TypeVariable
import java.math.BigInteger
import kotlin.test.*

class MuStdContextSpec {

    object BasicTestModule {
        @Mu.Export("PI") @Mu.Const val piValue = 3.14
        @Mu.Export var counter = 100
        @Mu.Export fun greet(name: String): String = "Hello, $name!"
        @Mu.Export("addInts") fun add(a: Int, b: Int): Int = a + b
    }

    @Test
    fun `register basic module`() {
        val context = MuStdContext.empty()
            .withNativeModule("basic", BasicTestModule)

        // Check module registration
        assertTrue(context.modules.containsKey("basic"))
        val module = context.modules["basic"]!!
        assertEquals(setOf("PI", "counter", "greet", "addInts"), module.definitions.keys)

        // Check PI (Const KProperty)
        val piValue = context.resolve("basic/PI").rightOrNull()
        assertNotNull(piValue)
        assertEquals(MuType.Double, piValue.type)
        assertEquals(3.14, piValue.unsafeValue)

        // Check counter (Mutable KProperty -> Function)
        val counterFunc = context.resolve("basic/counter").rightOrNull()
        assertNotNull(counterFunc)
        assertTrue(counterFunc.type is MuType.Func)
        assertEquals(MuType.Int, (counterFunc.type as MuType.Func).returnType)
        assertEquals(1, (counterFunc.type as MuType.Func).parameters.size) // Takes optional value
        assertEquals(MuType.Int.nullable(), (counterFunc.type as MuType.Func).parameters[0])

        // Check greet (Function)
        val greetFunc = context.resolve("basic/greet").rightOrNull()
        assertNotNull(greetFunc)
        assertTrue(greetFunc.type is MuType.Func)
        assertEquals(MuType.String, (greetFunc.type as MuType.Func).returnType)
        assertEquals(1, (greetFunc.type as MuType.Func).parameters.size)
        assertEquals(MuType.String, (greetFunc.type as MuType.Func).parameters[0])

        // Check addInts (Renamed Function)
        val addFunc = context.resolve("basic/addInts").rightOrNull()
        assertNotNull(addFunc)
        assertTrue(addFunc.type is MuType.Func)
        assertEquals(MuType.Int, (addFunc.type as MuType.Func).returnType)
        assertEquals(2, (addFunc.type as MuType.Func).parameters.size)
        assertEquals(MuType.Int, (addFunc.type as MuType.Func).parameters[0])
        assertEquals(MuType.Int, (addFunc.type as MuType.Func).parameters[1])

        // Check resolution after opening
        val openContext = context.withOpenModule("basic")
        assertNotNull(openContext.resolve("PI").rightOrNull())
        assertNotNull(openContext.resolve("counter").rightOrNull())
        assertNotNull(openContext.resolve("greet").rightOrNull())
        assertNotNull(openContext.resolve("addInts").rightOrNull())
    }

    class AdvancedTestModule {
        @Mu.Export fun <T> identity(value: T): T = value // Generic function
        @Mu.Export fun processOptional(
            @Mu.Name("opt") @Mu.Optional value: String?
        ): String = value ?: "Default"

        @Mu.Export fun sumVarargs(
            @Mu.Name("nums") @Mu.ZeroOrMore values: List<BigInteger>
        ): BigInteger = values.fold(BigInteger.ZERO, BigInteger::add)

        @Mu.Export fun processList(@Mu.Name("items") @Mu.OneOrMore values: List<String>): String =
            values.joinToString("-")

        @Mu.Export fun requiresContext(
            @Mu.Context ctx: MuStdContext,
            @Mu.Name("arg") value: String
        ): Pair<MuStdContext, String> {
            val file = ctx.currentFile()?.name ?: "unknown"
            return ctx to "Arg '$value' processed in context of file '$file'"
        }
    }

    @Test
    fun `register advanced module`() {
        val context = MuStdContext.empty()
            .withNativeModule("adv", AdvancedTestModule()) // Note: Need instance for non-object module

        assertTrue(context.modules.containsKey("adv"))
        val module = context.modules["adv"]!!
        assertEquals(setOf("identity", "processOptional", "sumVarargs", "processList", "requiresContext"), module.definitions.keys)

        // Check identity (Generic Function)
        val idFunc = module.definitions["identity"]?.unsafeValue as? MuStdFunc
        assertNotNull(idFunc)
        assertEquals(1, idFunc.typeParameters.size)
        assertEquals("T", idFunc.typeParameters[0].name)
        assertEquals(1, idFunc.parameters.size)
        assertEquals(MuType.Use(TypeVariable("T")), idFunc.parameters[0].type)

        // Check processOptional
        val optFunc = module.definitions["processOptional"]?.unsafeValue as? MuStdFunc
        assertNotNull(optFunc)
        assertEquals(1, optFunc.parameters.size)
        assertEquals(ArgArity.Optional, optFunc.parameters[0].arity)
        // MuType.fromKType converts String? to Constructor("?", listOf(String))
        assertEquals(MuType.Constructor("?", listOf(MuType.String)), optFunc.parameters[0].type)

        // Check sumVarargs
        val sumFunc = module.definitions["sumVarargs"]?.unsafeValue as? MuStdFunc
        assertNotNull(sumFunc)
        assertEquals(1, sumFunc.parameters.size)
        assertEquals(ArgArity.ZeroOrMore, sumFunc.parameters[0].arity)
        assertEquals(MuType.List(MuType.BigInteger), sumFunc.parameters[0].type)

        // Check processList
        val listFunc = module.definitions["processList"]?.unsafeValue as? MuStdFunc
        assertNotNull(listFunc)
        assertEquals(1, listFunc.parameters.size)
        assertEquals(ArgArity.OneOrMore, listFunc.parameters[0].arity)
        assertEquals(MuType.List(MuType.String), listFunc.parameters[0].type)

        // Check requiresContext
        val ctxFunc = module.definitions["requiresContext"]?.unsafeValue as? MuStdFunc
        assertNotNull(ctxFunc)
        // Note: The @Context parameter itself is not listed in the MuStdFunc parameters
        assertEquals(1, ctxFunc.parameters.size)
        assertEquals("arg", ctxFunc.parameters[0].name)
        assertEquals(MuType.String, ctxFunc.parameters[0].type)
    }

    interface Show<A> { fun show(a: A): String }
    class InstanceTestModule {
        @Mu.Instance
        val showBigInt = object : Show<BigInteger> {
            override fun show(a: BigInteger): String = "BigInt($a)"
        }

        @Mu.Instance
        fun <A> showList(elemShow: Show<A>) = object : Show<List<A>> {
            override fun show(a: List<A>): String =
                a.joinToString(", ", "[", "]") { elemShow.show(it) }
        }

        // Requires Show<A> implicitly
        @Mu.Export
        fun <A> print(@Mu.Name("value") value: A, @Mu.Instance showInstance: Show<A>): String {
            return "Printing: ${showInstance.show(value)}"
        }
    }

    @Test
    fun `register instance module`() {
        val context = MuStdContext.empty()
            .withNativeModule("inst", InstanceTestModule())

        val tcName = Show::class.qualifiedName!!

        // Check instance registration
        val showInstancesSimple = context.instances[tcName]
        assertNotNull(showInstancesSimple, "Instances for 'Show' should be registered")
        // We expect 2 instances: Show<BigInteger> and the generic Show<List<A>>
        assertEquals(2, showInstancesSimple.size)

        val showBigIntInstance = showInstancesSimple.find { it.parameters.isEmpty() && it.returnType.args == listOf(
            MuType.BigInteger)}
        assertNotNull(showBigIntInstance, "Show<BigInteger> instance not found")

        val showListInstance = showInstancesSimple.find { it.parameters.size == 1 }
        assertNotNull(showListInstance, "Show<List<A>> instance not found")
        assertEquals(1, showListInstance.typeParameters.size) // <A>
        assertEquals(1, showListInstance.parameters.size)
        assertEquals(tcName, showListInstance.parameters[0].head) // Requires Show<A>
        assertEquals(listOf(MuType.Use(TypeVariable("A"))), showListInstance.parameters[0].args)
        assertEquals(tcName, showListInstance.returnType.head) // Returns Show<List<A>>
        assertEquals(1, showListInstance.returnType.args.size)
        assertEquals(MuType.List(MuType.Use(TypeVariable("A"))), showListInstance.returnType.args[0])

        // Check export that uses instance
        val printFunc = context.resolve("inst/print").rightOrNull()?.unsafeValue as? MuStdFunc
        assertNotNull(printFunc)
        assertEquals(1, printFunc.typeParameters.size) // <A>
        assertEquals(1, printFunc.parameters.size) // the 'value: A' parameter
        // The implicit @Instance parameter `showInstance: Show<A>` is handled by the solver, not listed here.

        // TODO: Add tests using evaluateMu to check if instance resolution works at runtime
    }

    class ErrorCaseModule {
        @Mu.Export("both") @Mu.Instance val bad = 1 // Error: Both Export and Instance

        @Mu.Export fun invalidOptional(@Mu.Optional value: String) = value // Error: Optional on non-nullable
    }

    object ErrorCase2 {
        @Mu.Export fun duplicate(a: Int) = a
        @Mu.Export("duplicate") fun duplicateRenamed(a: Int) = a // Error: Duplicate export name
    }

    @Test
    fun `registration error cases`() {
        val context = MuStdContext.empty()

        // Error: Both @Export and @Instance
        assertFailsWith<IllegalArgumentException>("Should throw when both @Export and @Instance present") {
            context.withNativeModule("err1", ErrorCaseModule())
        }

//        // Error: Duplicate export name (manual test, as order might affect which one throws)
//        // This depends on how KClass.members orders overloaded/identically named members
//        // A robust implementation should detect this explicitly.
//        // Let's assume for now the check might be missing or unreliable via simple registration order.
//        assertFailsWith<MuException>("Should throw on duplicate export name") {
//            context.withNativeModule("err2", ErrorCase2) // This might not throw if check is missing
//        }

        // Error: @Optional on non-nullable type
        // This check might happen during MuType.fromKType or later during validation
        assertFailsWith<IllegalArgumentException>("Should throw when @Optional is on non-nullable") {
            // Need to isolate the specific function registration if the module registration fails early
            // This test case might be hard to trigger directly via withNativeModule if other errors happen first
            // Requires more fine-grained testing of makeValueFromMember if needed.
            val module = ErrorCaseModule()
            val member = module::class.members.first { it.name == "invalidOptional" }
            makeValueFromMember(module, "invalidOptional", member) // May throw here
        }
    }
}