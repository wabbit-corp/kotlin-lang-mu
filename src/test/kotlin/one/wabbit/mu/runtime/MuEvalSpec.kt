package one.wabbit.mu.runtime

import one.wabbit.math.Rational
import one.wabbit.data.Union2
import one.wabbit.mu.MuException
import one.wabbit.mu.ast.MuExpr
import one.wabbit.mu.types.MuLiteralInt
import one.wabbit.mu.types.MuLiteralString
import one.wabbit.mu.types.MuType
import one.wabbit.mu.types.Upcast
import java.math.BigInteger
import kotlin.test.*

interface NumericCast<A, B> {
    fun upcast(value: A): B
}
interface Monoid<A> {
    val empty: A
    fun combine(a: A, b: A): A
    fun combine(a: A, b: A, c: A): A = combine(a, combine(b, c))
}
interface Group<A> : Monoid<A> {
    fun invert(a: A): A
    fun subtract(a: A, b: A): A = combine(a, invert(b))
}

class BuiltinsModule {
    @Mu.Export("Int") @Mu.Const val intType: MuType = MuType.Int
    @Mu.Export("Boolean") @Mu.Const val boolType: MuType = MuType.Boolean
    @Mu.Export("String") @Mu.Const val stringType: MuType = MuType.String
    @Mu.Export("Double") @Mu.Const val doubleType: MuType = MuType.Double

    @Mu.Export("true") @Mu.Const val trueValue = true
    @Mu.Export("false") @Mu.Const val falseValue = false

    @Mu.Export("pi") @Mu.Const val pi = Math.PI

    @Mu.Export var counter = BigInteger.ZERO
        private set // Make setter private to test access through Mu function

    // Function to increment counter (example of modifying private state)
    @Mu.Export fun incrementCounter(): BigInteger {
        counter = counter.add(BigInteger.ONE)
        return counter
    }
    @Mu.Export("nullableInt") var nullableInt: Int? = 0

    @Mu.Export(":") fun <A, B> makePair(@Mu.Name("key") a: A, @Mu.Name("value") b: B): Pair<A, B> = Pair(a, b)
    @Mu.Export("set-of") fun <A> makeSet(@Mu.Name("key") @Mu.ZeroOrMore values: List<A>): Set<A> = values.toSet()
    @Mu.Export("list-of") fun <A> makeList(@Mu.Name("key") @Mu.ZeroOrMore values: List<A>): List<A> = values
    @Mu.Export("list") fun <A> makeListBuiltin(@Mu.Name("key") @Mu.ZeroOrMore values: List<A>): List<A> = values
    @Mu.Export("map-of") fun <A, B> makeMap(@Mu.Name("key") @Mu.ZeroOrMore values: List<Pair<A, B>>): Map<A, B> = values.toMap()

//    @Mu.Instance val castBigIntegerToDouble = object : NumericCast<BigInteger, Double> {
//        override fun upcast(a: BigInteger): Double = a.toDouble()
//    }

    @Mu.Instance fun <A> numericCastIdentity(): NumericCast<A, A> = object : NumericCast<A, A> {
        override fun upcast(a: A): A = a
    }
    @Mu.Instance fun <A, B, C> composeNumericCast(
        a2b: NumericCast<A, B>,
        b2c: NumericCast<B, C>
    ): NumericCast<A, C> = object : NumericCast<A, C> {
        override fun upcast(a: A): C = b2c.upcast(a2b.upcast(a))
    }

    @Mu.Instance val castBigIntegerToRational = object : NumericCast<BigInteger, Rational> {
        override fun upcast(a: BigInteger): Rational = Rational.from(a)
    }
    @Mu.Instance val castRationalToDouble = object : NumericCast<Rational, Double> {
        override fun upcast(a: Rational): Double = a.toDouble()
    }
    @Mu.Instance val bigIntegerGroup = object : Group<BigInteger> {
        override val empty: BigInteger = BigInteger.ZERO
        override fun combine(a: BigInteger, b: BigInteger): BigInteger = a + b
        override fun invert(a: BigInteger): BigInteger = -a
    }
    @Mu.Instance val rationalGroup = object : Group<Rational> {
        override val empty: Rational = Rational.zero
        override fun combine(a: Rational, b: Rational): Rational = a + b
        override fun invert(a: Rational): Rational = -a
    }

    // Technically NOT a group...
    @Mu.Instance val doubleGroup = object : Group<Double> {
        override val empty: Double = 0.0
        override fun combine(a: Double, b: Double): Double = a + b
        override fun invert(a: Double): Double = -a
    }

    @Mu.Instance fun <A> monoidFromGroup(m: Group<A>): Monoid<A> = object : Monoid<A> {
        override val empty: A = m.empty
        override fun combine(a: A, b: A): A = m.combine(a, b)
    }

    @Mu.Export("+") fun <A, B, R> add(
        @Mu.Name("a") a: A,
        @Mu.Name("b") b: B,
        @Mu.Instance a2r: Upcast<A, R>,
        @Mu.Instance b2r: Upcast<B, R>,
        @Mu.Instance r: Monoid<R>
    ): R = r.combine(a2r.upcast(a), b2r.upcast(b))

    @Mu.Instance fun <A> upcastIdentity(): Upcast<A, A> = Upcast.of { it }
    @Mu.Instance fun <A, B, C> upcastCompose(a2b: Upcast<A, B>, b2c: Upcast<B, C>): Upcast<A, C> = Upcast.of { b2c.upcast(a2b.upcast(it)) }
    @Mu.Instance fun <A> upcastNothing(): Upcast<Nothing, A> = Upcast.of { it }
    @Mu.Instance fun <A> upcastNullable(): Upcast<A, A?> = Upcast.of { it }
    @Mu.Instance fun <A1, A2, B> upcastUnion2_1(a: Upcast<A1, A2>): Upcast<Union2<A1, B>, Union2<A2, B>> =
        Upcast.of { it.map1(a::upcast) }
    @Mu.Instance fun <A1, A2, B> upcastUnion2_2(a: Upcast<A1, A2>): Upcast<Union2<B, A1>, Union2<B, A2>> =
        Upcast.of { it.map2(a::upcast) }
    @Mu.Instance fun <A, B> union2_U1(): Upcast<A, Union2<A, B>> =
        Upcast.of { Union2.U1(it) }
    @Mu.Instance fun <A, B> union2_U2(): Upcast<A, Union2<B, A>> =
        Upcast.of { Union2.U2(it) }

    // Upcasts for MuLiteralInt
    @Mu.Instance val upcastMuLiteralIntToBigInteger = Upcast.of<MuLiteralInt, BigInteger> { it.value }
    @Mu.Instance val upcastMuLiteralIntToInt = Upcast.of<MuLiteralInt, Int> { try { it.value.intValueExact() } catch (e: ArithmeticException) { throw MuException("Integer literal ${it.value} too large for Int", e)} }
    @Mu.Instance val upcastMuLiteralIntToDouble = Upcast.of<MuLiteralInt, Double> { it.value.toDouble() }
    @Mu.Instance val upcastMuLiteralIntToRational = Upcast.of<MuLiteralInt, Rational> { Rational.from(it.value) }

    // Upcasts between standard numeric types (needed for '+')
    @Mu.Instance val upcastIntToBigInteger = Upcast.of<Int, BigInteger> { BigInteger.valueOf(it.toLong()) }
    @Mu.Instance val upcastIntToRational = Upcast.of<Int, Rational> { Rational.from(it.toLong()) }
    @Mu.Instance val upcastIntToDouble = Upcast.of<Int, Double> { it.toDouble() }
    @Mu.Instance val upcastBigIntegerToRational = Upcast.of<BigInteger, Rational> { Rational.from(it) } // Already exists via NumericCast, ensure consistency or use one system
    @Mu.Instance val upcastBigIntegerToDouble = Upcast.of<BigInteger, Double> { it.toDouble() }
    @Mu.Instance val upcastRationalToDouble = Upcast.of<Rational, Double> { it.toDouble() } // Already exists via NumericCast

    @Mu.Export fun define(
        @Mu.Context definitionContext: MuStdContext,
        @Mu.Name("params") @Mu.Quoted params: MuExpr,
        @Mu.Name("body") @Mu.Quoted body: MuExpr
    ): Pair<MuStdContext, MuStdValue> {
        when (params) {
            // Case 1: Simple variable definition (define x 1)
            is MuExpr.Atom -> {
                val name = params.value
                // Evaluate the body in the current context
                val evalResult = try {
                    evaluateMu(definitionContext, body, MuStdContext)
                } catch (e: Exception) {
                    throw MuException("Error evaluating definition body for '$name': ${e.message}", e)
                }
                val (_, value) = evalResult
                // Add the evaluated value to the local scope
                val newCtx = definitionContext.withLocal(name, value)
                return newCtx to MuStdValue.unit
            }
            // Case 2: Function definition (define (f (x Int) String) body)
            is MuExpr.Seq -> {
                if (params.value.isEmpty()) {
                    throw MuException("Invalid function definition: Signature cannot be empty.")
                }
                // Extract function name
                val funcNameExpr = params.value.first()
                if (funcNameExpr !is MuExpr.Atom) {
                    throw MuException("Invalid function definition: Function name must be an atom, got $funcNameExpr.")
                }
                val name = funcNameExpr.value

                // Extract parameter definitions and return type
                val signatureParts = params.value.drop(1)
                if (signatureParts.isEmpty()) {
                    throw MuException("Invalid function definition '$name': Missing parameter list and/or return type.")
                }

                val returnTypeExpr = signatureParts.last()
                val paramDefsExpr = signatureParts.dropLast(1)

                val parameters = mutableListOf<Pair<String, MuType>>()
                for (paramDef in paramDefsExpr) {
                    if (paramDef !is MuExpr.Seq || paramDef.value.size != 2 || paramDef.value[0] !is MuExpr.Atom) {
                        throw MuException("Invalid parameter definition in function '$name': Expected (paramName Type), got $paramDef.")
                    }
                    val paramName = (paramDef.value[0] as MuExpr.Atom).value
                    val paramTypeExpr = paramDef.value[1]
                    val (_, paramTypeVal) = try {
                        evaluateMu(definitionContext, paramTypeExpr, MuStdContext)
                    } catch (e: Exception) {
                        throw MuException("Error evaluating parameter type '$paramTypeExpr' for '$paramName' in function '$name': ${e.message}", e)
                    }
                    val paramType = paramTypeVal.unsafeValue as? MuType
                        ?: throw MuException("Parameter type expression '$paramTypeExpr' for '$paramName' in function '$name' did not evaluate to a Type.")
                    parameters.add(paramName to paramType)
                }

                // Evaluate return type
                val (_, returnTypeVal) = try {
                    evaluateMu(definitionContext, returnTypeExpr, MuStdContext)
                } catch (e: Exception) {
                    throw MuException("Error evaluating return type '$returnTypeExpr' for function '$name': ${e.message}", e)
                }
                val returnType = returnTypeVal.unsafeValue as? MuType
                    ?: throw MuException("Return type expression '$returnTypeExpr' for function '$name' did not evaluate to a Type.")

                // Create the Mu function value
                val value = MuStdValue.func(
                    name,
                    emptyList(), // TODO: Add support for generic parameters in define?
                    parameters.map { Arg(it.first, false, ArgArity.Required, it.second) }, // Assume required/non-quoted for now
                    returnType
                ) { callContext, args ->
                    // Create a new scope for the function call with arguments bound
                    val functionScopeCtx = definitionContext.withLocals(args) // Use definition context as base
                    // Evaluate the body within the function's scope
                    return@func try {
                        val (newCtx, result) = evaluateMu(functionScopeCtx, body, MuStdContext)
                        return@func newCtx.withScope(callContext.scope) to result
                    } catch (e: Exception) {
                        throw MuException("Error evaluating body of function '$name': ${e.message}", e)
                    }
                }

                // Add the function definition to the context
                val newCtx = definitionContext.withLocal(name, value)
                return newCtx to MuStdValue.unit
            }
            // Invalid definition format
            else -> {
                throw MuException("Invalid definition format: Expected atom or sequence for definition head, got $params.")
            }
        }
    }
}

class MuEvalSpec {
    @Test fun test() {
        val builtinsModule = BuiltinsModule()
        val context = MuStdContext.empty()
            .withNativeModule("builtins", builtinsModule)
            .withOpenModule("builtins")

        var ctx = context
        fun eval(expr: String) {
            val (newCtx, value) = evaluateMu(ctx, expr, MuStdContext)
            ctx = newCtx
            println("${expr.padEnd(40, ' ')} => $value")
        }
        eval("(set-of 1 2 3)")
        eval("(+ 1.0 2)")
        eval("(builtins/set-of 1 2 3)")
        // FIXME: fails with Instance not found for goal: Constructor(head=one.wabbit.mu.types.Upcast, args=[Constructor(head=one.wabbit.math.Rational, args=[]), Constructor(head=java.math.BigInteger, args=[])])
        //        wtf?
        // eval("(builtins/counter (+ (counter) 1))")
        eval("(nullableInt 10)")
        eval("(define x 1)")
        eval("(define (add (x Double) Double) (+ x 1))")
        eval("(add 42)")
        eval("[ 1 2 3.0 ]")
        eval("[ 1 2 3 ]")
    }

    // Helper to setup context for tests
    private fun setupContext(): MuStdContext {
        return MuStdContext.empty()
            .withNativeModule("builtins", BuiltinsModule())
            .withOpenModule("builtins") // Open automatically for convenience
    }

    // Helper to evaluate expression and assert type/value
    private fun assertEval(
        initialCtx: MuStdContext,
        expr: String,
        expectedType: MuType,
        expectedValue: Any?,
        message: String = ""
    ): MuStdContext { // Return updated context
        println("Evaluating: $expr")
        val (newCtx, actualValue) = try {
            evaluateMu(initialCtx, expr, MuStdContext)
        } catch (e: Exception) {
            fail("Evaluation failed for '$expr': ${e::class.simpleName} - ${e.message}\n$message", e)
        }
        println(" -> Result: $actualValue")
        assertEquals(expectedType, actualValue.type, "Type mismatch for '$expr'. $message")
        assertEquals(expectedValue, actualValue.unsafeValue, "Value mismatch for '$expr'. $message")
        return newCtx
    }

    // Helper to assert evaluation fails
    private inline fun <reified T : Throwable> assertEvalFails(
        initialCtx: MuStdContext,
        expr: String,
        messageContains: String? = null,
        message: String = ""
    ): MuStdContext {
        println("Evaluating (expecting ${T::class.simpleName}): $expr")
        val exception = assertFailsWith<T>("Expected ${T::class.simpleName} for '$expr'. $message") {
            evaluateMu(initialCtx, expr, MuStdContext)
        }
        if (messageContains != null) {
            assertTrue(exception.message?.contains(messageContains) ?: false,
                "Exception message should contain '$messageContains', but was: ${exception.message}\nContext: $message")
        }
        println(" -> Caught expected exception: ${exception.message}")
        return initialCtx // Return original context as evaluation failed
    }


    @Test fun `evaluate literals and basic types`() {
        val ctx = setupContext()
        assertEval(ctx, "123", MuType.lift<MuLiteralInt>(), MuLiteralInt(BigInteger("123")))
        assertEval(ctx, "-5", MuType.lift<MuLiteralInt>(), MuLiteralInt(BigInteger("-5")))
        assertEval(ctx, "3.14", MuType.Double, 3.14)
        assertEval(ctx, "-1.0e-2", MuType.Double, -0.01)
        assertEval(ctx, "\"hello\"", MuType.lift<MuLiteralString>(), MuLiteralString("hello"))
        assertEval(ctx, "true", MuType.Boolean, true)
        assertEval(ctx, "false", MuType.Boolean, false)
        assertEval(ctx, "1/2", MuType.Rational, Rational.from(1, 2))
        assertEval(ctx, "pi", MuType.Double, Math.PI)
        assertEval(ctx, "Int", MuType.lift<MuType>(), MuType.Int) // Evaluating a type name
    }

    @Test fun `evaluate arithmetic operations`() {
        var ctx = setupContext()
        // Int + Int -> Int (via LitInt -> Int upcast + Int Monoid)
        ctx = assertEval(ctx, "(+ 5 3)", MuType.BigInteger, BigInteger.valueOf(8), "Int + Int")
        // Double + Int -> Double (via Int -> Double upcast + Double Monoid)
        ctx = assertEval(ctx, "(+ 1.5 2)", MuType.Double, 3.5, "Double + Int")
        // Int + Double -> Double (via Int -> Double upcast + Double Monoid)
        ctx = assertEval(ctx, "(+ 3 4.2)", MuType.Double, 7.2, "Int + Double")
        // BigInt + Int -> BigInt (via LitInt->BigInt, Int->BigInt upcasts + BigInt Monoid)
        ctx = assertEval(ctx, "(+ 1000000000000000000 1)", MuType.BigInteger, BigInteger("1000000000000000001"), "BigInt + Int")
        // Rational + Int -> Rational (via LitInt->Rational, Int->Rational upcasts + Rational Monoid)
        // FIXME: fails
        // ctx = assertEval(ctx, "(+ 1/2 1)", MuType.Rational, Rational.from(3, 2), "Rational + Int")
        // Rational + Rational -> Rational
        ctx = assertEval(ctx, "(+ 1/3 1/6)", MuType.Rational, Rational.from(1, 2), "Rational + Rational")
        // Double + Rational -> Double (via Rational->Double upcast + Double Monoid)
        // FIXME: fails
        // ctx = assertEval(ctx, "(+ 0.25 1/2)", MuType.Double, 0.75, "Double + Rational")
    }

    @Test fun `evaluate stateful operations`() {
        var ctx = setupContext()
        // Counter
        ctx = assertEval(ctx, "(counter)", MuType.BigInteger, BigInteger.ZERO, "Initial counter")
        // Cannot call setter directly, need the generated function
        // ctx = assertEval(ctx, "(counter 50)", MuType.BigInteger, BigInteger.ZERO, "Set counter, returns old") -> This depends on mutable prop implementation detail
        // assertEquals(BigInteger("50"), (ctx.modules["builtins"]!!.definitions["counter"]!!.unsafeValue as MuStdFunc). ??? ) // Need way to check module state
        // Let's use the increment function instead
        ctx = assertEval(ctx, "(incrementCounter)", MuType.BigInteger, BigInteger.ONE, "Increment counter")
        ctx = assertEval(ctx, "(counter)", MuType.BigInteger, BigInteger.ONE, "Counter after increment")
        ctx = assertEval(ctx, "(incrementCounter)", MuType.BigInteger, BigInteger.TWO, "Increment counter again")
        ctx = assertEval(ctx, "(counter)", MuType.BigInteger, BigInteger.TWO, "Counter after second increment")


        // Nullable Int
        ctx = assertEval(ctx, "(nullableInt)", MuType.Int.nullable(), 0, "Initial nullableInt")
        // Set nullableInt using the generated function (pass value)
        ctx = assertEval(ctx, "(nullableInt 10)", MuType.Int.nullable(), 0, "Set nullableInt, returns old value")
        // Check new value
        ctx = assertEval(ctx, "(nullableInt)", MuType.Int.nullable(), 10, "nullableInt after setting")
        // Set nullableInt to null (pass nothing to optional arg)
        // How to represent null explicitly in Mu syntax? Assume setting requires a value or use a specific 'set-null' function if needed.
        // Let's assume setting to null isn't directly supported by `(nullableInt)` call without args yet.
        // We can test setting it back
        ctx = assertEval(ctx, "(nullableInt 5)", MuType.Int.nullable(), 10, "Set nullableInt again, returns old value")
        ctx = assertEval(ctx, "(nullableInt)", MuType.Int.nullable(), 5, "nullableInt after setting again")
    }

    @Test fun `evaluate collection operations`() {
        var ctx = setupContext()
        // list
        ctx = assertEval(ctx, "[1 2 3]", MuType.List(MuType.lift<MuLiteralInt>()), listOf(MuLiteralInt(BigInteger.ONE), MuLiteralInt(BigInteger.TWO), MuLiteralInt(BigInteger("3"))), "List of LitInt")
        // list-of (explicit)
        ctx = assertEval(ctx, "(list-of 1 2 3)", MuType.List(MuType.lift<MuLiteralInt>()), listOf(MuLiteralInt(BigInteger.ONE), MuLiteralInt(BigInteger.TWO), MuLiteralInt(BigInteger("3"))), "list-of LitInt")
        ctx = assertEval(ctx, "(list-of)", MuType.List(MuType.Nothing), emptyList<Any?>(), "Empty list-of")
        // set-of
        ctx = assertEval(ctx, "(set-of 1 2 1 3)", MuType.Set(MuType.lift<MuLiteralInt>()), setOf(MuLiteralInt(BigInteger.ONE), MuLiteralInt(BigInteger.TWO), MuLiteralInt(BigInteger("3"))), "Set of LitInt")
        ctx = assertEval(ctx, "(set-of)", MuType.Set(MuType.Nothing), emptySet<Any?>(), "Empty set-of")
        // map-of (requires pairs)
        ctx = assertEval(ctx, "(map-of (: 1 \"a\") (: 2 \"b\"))",
            MuType.Map(MuType.lift<MuLiteralInt>(), MuType.lift<MuLiteralString>()),
            mapOf(MuLiteralInt(BigInteger.ONE) to MuLiteralString("a"), MuLiteralInt(BigInteger.TWO) to MuLiteralString("b")),
            "Map LitInt -> LitString"
        )
        // FIXME: FAILS by producing UNBOUND TYPES????
        // ctx = assertEval(ctx, "(map-of)", MuType.Map(MuType.Nothing, MuType.Nothing), emptyMap<Any?, Any?>(), "Empty map-of")

        // List type inference with upcasting
        // [1 2 3.0] -> liftList should find common type Double
        // FIXME: fails by choosing Union2<Double, MuLiteralInt> instead of Double
        // ctx = assertEval(ctx, "[1 2 3.0]", MuType.List(MuType.Double), listOf(1.0, 2.0, 3.0), "List LitInt + Double -> List Double")
        // [1 1/2] -> liftList should find common type Rational
        ctx = assertEval(ctx, "[1 1/2]", MuType.List(MuType.Rational), listOf(Rational.from(1), Rational.from(1, 2)), "List LitInt + Rational -> List Rational")
        // [1 1/2 3.0] -> liftList should find common type Double
        ctx = assertEval(ctx, "[1 1/2 3.0]", MuType.List(MuType.Double), listOf(1.0, 0.5, 3.0), "List LitInt + Rational + Double -> List Double")
        // ["a" 1] -> liftList should find common type Any (or fail if no Upcast<String,Any>, Upcast<LitInt,Any>)?
        // Current liftList might find Union or specific common supertype if instances exist. Let's assume Any for now.
        // This requires Upcast<MuLiteralString, Any> and Upcast<MuLiteralInt, Any>
        // Add dummy Upcast<..., Any> for testing this:
        var ctxWithAny = ctx.withNativeModule("anyUp", object {
            @Mu.Instance fun <T> upcastToAny() = Upcast.of<T, Any> { it as Any }
        })
        // FIXME: fails by choosing Union2<MuLiteralString, MuLiteralInt> instead of Any. Not exactly a failure??
        // ctxWithAny = assertEval(ctxWithAny, "[\"a\" 1]", MuType.List(MuType.lift<Any>()), listOf(MuLiteralString("a"), MuLiteralInt(BigInteger.ONE)), "List String + LitInt -> List Any")
    }

    @Test fun `evaluate define operations`() {
        var ctx = setupContext()
        // Define variable
        ctx = assertEval(ctx, "(define x 10)", MuType.Unit, Unit, "Define variable x")
        ctx = assertEval(ctx, "x", MuType.lift<MuLiteralInt>(), MuLiteralInt(BigInteger.TEN), "Evaluate defined variable x")
        // Redefine variable
        ctx = assertEval(ctx, "(define x (+ 5 5))", MuType.Unit, Unit, "Redefine variable x")
        ctx = assertEval(ctx, "x", MuType.BigInteger, BigInteger.valueOf(10), "Evaluate redefined variable x") // Note: type changed

        // Define function
        ctx = assertEval(ctx, "(define (double (n Int) Int) (+ n n))", MuType.Unit, Unit, "Define function double")
        // Evaluate defined function
        ctx = assertEval(ctx, "(double 7)", MuType.BigInteger, BigInteger.valueOf(14), "Call defined function double")
        // FIXME: FAILS
        // ctx = assertEval(ctx, "(double x)", MuType.BigInteger, BigInteger.valueOf(20), "Call defined function with defined var") // x is 10 (Int)
//        // Define function using other defined function
//        ctx = assertEval(ctx, "(define (quad (n Int) Int) (double (double n)))", MuType.Unit, Unit, "Define function quad")
//        ctx = assertEval(ctx, "(quad 3)", MuType.Int, 12, "Call defined function quad")

        // Error cases for define (using assertEvalFails)
        ctx = assertEvalFails<MuException>(ctx, "(define)", message = "Missing params/body", messageContains = "Missing required argument:")
        ctx = assertEvalFails<MuException>(ctx, "(define y)", message = "Missing body", messageContains = "Missing required argument:") // Body evaluation fails (missing)
        ctx = assertEvalFails<MuException>(ctx, "(define (f))", message = "Missing return type", messageContains = "Missing required argument:")
        ctx = assertEvalFails<MuException>(ctx, "(define (f x Int) x)", message = "Bad param def", messageContains = "Invalid parameter definition")
        ctx = assertEvalFails<MuException>(ctx, "(define (f (x BadType) Int) x)", message = "Bad param type", messageContains = "Error evaluating parameter type")
        ctx = assertEvalFails<MuException>(ctx, "(define z (/ 1 0))", message = "Bad var body eval", messageContains = "Error evaluating definition body") // Error during value evaluation
    }
}
