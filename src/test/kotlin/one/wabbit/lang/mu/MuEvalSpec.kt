package one.wabbit.lang.mu

import one.wabbit.lang.mu.std.*
import one.wabbit.math.Rational
import one.wabbit.data.Union2
import java.math.BigInteger
import kotlin.test.Test

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
    @Mu.Export("counter") var counter = BigInteger.ZERO
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

    @Mu.Instance fun <A> upcastIdentity(): Upcast<A, A> =
        Upcast.of { it }
    @Mu.Instance fun <A, B, C> upcastCompose(a2b: Upcast<A, B>, b2c: Upcast<B, C>): Upcast<A, C> =
        Upcast.of { b2c.upcast(a2b.upcast(it)) }
    @Mu.Instance fun <A> upcastNothing(): Upcast<Nothing, A> =
        Upcast.of { it }
    @Mu.Instance fun <A> upcastNullable(): Upcast<A, A?> =
        Upcast.of { it }
    @Mu.Instance fun <A1, A2, B> upcastUnion2_1(a: Upcast<A1, A2>): Upcast<Union2<A1, B>, Union2<A2, B>> =
        Upcast.of { it.map1(a::upcast) }
    @Mu.Instance fun <A1, A2, B> upcastUnion2_2(a: Upcast<A1, A2>): Upcast<Union2<B, A1>, Union2<B, A2>> =
        Upcast.of { it.map2(a::upcast) }
    @Mu.Instance fun <A, B> union2_U1(): Upcast<A, Union2<A, B>> =
        Upcast.of { Union2.U1(it) }
    @Mu.Instance fun <A, B> union2_U2(): Upcast<A, Union2<B, A>> =
        Upcast.of { Union2.U2(it) }

    @Mu.Instance val castMuLiteralIntToBigInteger = Upcast.of<MuLiteralInt, BigInteger> { it.value }
    @Mu.Instance val castMuLiteralIntToInt = Upcast.of<MuLiteralInt, Int> { it.value.intValueExact() }
    @Mu.Instance val castMuLiteralIntToDouble = Upcast.of<MuLiteralInt, Double> { it.value.toDouble() }
    @Mu.Instance val castMuLiteralIntToRational = Upcast.of<MuLiteralInt, Rational> { Rational.from(it.value) }

    @Mu.Export fun test(arg: Union2<BigInteger, String>): Unit = println(arg)

    @Mu.Export fun define(
        @Mu.Context definitionContext: MuStdContext,
        @Mu.Name("params") @Mu.Quoted params: MuExpr,
        @Mu.Name("body") @Mu.Quoted body: MuExpr
    ): Pair<MuStdContext, MuStdValue> {
        when (params) {
            is MuExpr.Atom -> {
                val name = params.value
                val (_, value) = evaluateMu(definitionContext, body, MuStdContext.Companion)
                val newCtx = definitionContext.withLocal(name, value)
                return newCtx to MuStdValue.unit
            }
            is MuExpr.Seq -> {
                val name = (params.value.first() as MuExpr.Atom).value
                val parameters = params.value.drop(1).dropLast(1).map {
                    val (name, type) = (it as MuExpr.Seq).value
                    val (_, typeEvaluated) = evaluateMu(definitionContext, type, MuStdContext.Companion)
                    (name as MuExpr.Atom).value to (typeEvaluated.unsafeValue as MuType)
                }
                val resultTypeExpr = params.value.last()
                val resultType = evaluateMu(definitionContext, resultTypeExpr, MuStdContext.Companion).second.unsafeValue as MuType

                val value = MuStdValue.func(
                    name,
                    emptyList(),
                    parameters.map { Arg(it.first, false, ArgArity.Required, it.second) },
                    resultType
                ) { ctx, args ->
                    val ctx = definitionContext.withLocals(args)
                    return@func evaluateMu(ctx, body, MuStdContext.Companion)
                }

                val newCtx = definitionContext.withLocal(name, value)
                return newCtx to MuStdValue.unit
            }
            else -> {
                throw IllegalArgumentException("Invalid params: $params")
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
            val (newCtx, value) = evaluateMu(ctx, expr, MuStdContext.Companion)
            ctx = newCtx
            println("${expr.padEnd(40, ' ')} => $value")
        }
        eval("(set-of 1 2 3)")
        eval("(+ 1.0 2)")
        eval("(builtins/set-of 1 2 3)")
        eval("(builtins/counter (+ (counter) 1))")
        eval("(nullableInt 10)")
        eval("(define x 1)")
        eval("(define (add (x Double) Double) (+ x 1))")
        eval("(add 42)")
        eval("[ 1 2 3.0 ]")
        eval("[ 1 2 3 ]")
    }
}
