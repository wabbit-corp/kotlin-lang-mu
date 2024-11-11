package lang.mu

import kotlinx.serialization.Serializable
import one.wabbit.serializers.BigIntegerSerializer
import java.math.BigInteger

@Serializable
sealed interface MuExpr {
    @Serializable data class Integer(val value: @Serializable(with=BigIntegerSerializer::class) BigInteger) : MuExpr
    @Serializable data class Double(val value: kotlin.Double) : MuExpr
    @Serializable data class Rational(val value: one.wabbit.math.Rational) : MuExpr
    @Serializable data class String(val value: kotlin.String) : MuExpr
    @Serializable data class Atom(val value: kotlin.String) : MuExpr
    @Serializable data class Seq(val value: List<MuExpr>) : MuExpr

    fun format(): kotlin.String = when (this) {
        is Integer -> value.toString()
        is Double -> value.toString()
        is Rational -> value.toString()
        is String -> "\"$value\""
        is Atom -> value
        is Seq -> "(${value.joinToString(" ") { it.format() }})"
    }
}
