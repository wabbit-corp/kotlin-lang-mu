package one.wabbit.lang.mu

import one.wabbit.parsing.TextAndPosSpan
import java.math.BigInteger

data class Spanned<Value>(val value: Value, val span: TextAndPosSpan)

sealed interface MuParsedExpr {
    data class Integer(val value: Spanned<BigInteger>) : MuParsedExpr
    data class Rational(val value: Spanned<one.wabbit.math.Rational>) : MuParsedExpr
    data class Real(val value: Spanned<Double>) : MuParsedExpr

    data class Atom(val name: Spanned<kotlin.String>) : MuParsedExpr
    data class String(val value: Spanned<kotlin.String>) : MuParsedExpr

    data class Seq(val value: kotlin.collections.List<MuParsedExpr>) : MuParsedExpr
    data class List(val value: kotlin.collections.List<MuParsedExpr>) : MuParsedExpr
    data class Map(val value: kotlin.collections.List<Pair<MuParsedExpr, MuParsedExpr>>) : MuParsedExpr

    fun lower(): MuExpr = when (this) {
        is Integer -> MuExpr.Integer(value.value)
        is Real -> MuExpr.Double(value.value)
        is Rational -> MuExpr.Rational(value.value)
        is Atom -> MuExpr.Atom(name.value)
        is String -> MuExpr.String(value.value)
        is Seq -> MuExpr.Seq(value.map { it.lower() })
        is List -> MuExpr.Seq(listOf(MuExpr.Atom("list")) + value.map { it.lower() })
        is Map -> MuExpr.Seq(listOf(MuExpr.Atom("map")) + value.map { (k, v) ->
            MuExpr.Seq(listOf(MuExpr.Atom("pair"), k.lower(), v.lower()))
        })
    }
}
