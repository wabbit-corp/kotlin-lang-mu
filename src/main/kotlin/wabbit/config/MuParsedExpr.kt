//package wabbit.config
//
//import std.parsing.Span3
//import java.math.BigInteger
//
//data class Spanned<Value>(val value: Value, val span: Span3)
//
//sealed interface MuParsedExpr {
//    data class Integer(val value: Spanned<BigInteger>) : MuParsedExpr {
//        override fun toString(): kotlin.String = "${value.value}::INT"
//    }
//    data class Rational(val value: Spanned<std.base.Rational>) : MuParsedExpr {
//        override fun toString(): kotlin.String = "${value.value}::INT"
//    }
//    data class Real(val value: Spanned<Double>) : MuParsedExpr {
//        override fun toString(): kotlin.String = "${value.value}::REAL"
//    }
//
//    data class Atom(val name: kotlin.String) : MuParsedExpr {
//        override fun toString(): kotlin.String = "$name::SYM"
//    }
//    data class String(val value: kotlin.String) : MuParsedExpr {
//        override fun toString(): kotlin.String = "\"$value\""
//    }
//
//    data class Seq(val value: kotlin.collections.List<MuParsedExpr>) : MuParsedExpr {
//        override fun toString(): kotlin.String =
//            value.joinToString(" ", "(", ")")
//    }
//    data class List(val value: kotlin.collections.List<MuParsedExpr>) : MuParsedExpr {
//        override fun toString(): kotlin.String =
//            value.joinToString(" ", "[", "]")
//    }
//    data class Map(val value: kotlin.collections.List<Pair<MuParsedExpr, MuParsedExpr>>) : MuParsedExpr {
//        override fun toString(): kotlin.String =
//            value.joinToString(", ", "{", "}") { (k, v) -> "$k : $v" }
//    }
//
//    fun lower(): MuExpr {
//        return when (this) {
//            is Integer  -> MuExpr.Integer(value.value)
//            is Real     -> MuExpr.Double(value.value)
//            is Rational -> MuExpr.Rational(value.value)
//            is Atom     -> MuExpr.Atom(name)
//            is String   -> MuExpr.String(value)
//            is Seq      -> MuExpr.Seq(value.map { it.lower() })
//            is List     -> MuExpr.Seq(listOf(MuExpr.Atom("list")) + value.map { it.lower() })
//            is Map      -> MuExpr.Seq(listOf(MuExpr.Atom("map")) + value.map { (k, v) ->
//                MuExpr.Seq(listOf(MuExpr.Atom("pair"), k.lower(), v.lower()))
//            })
//        }
//    }
//}
