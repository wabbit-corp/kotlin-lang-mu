//package wabbit.config
//
//import std.data.Validated
//import java.math.BigInteger
//import kotlin.reflect.KType
//import kotlin.reflect.typeOf
//
//data class MuFunc<V>(
//    val name: kotlin.String?,
//    val args: List<Arg<V>>,
//    val body: MuFuncBody<V>,
//    val unquote: Boolean = false,
//    val capturedEnv: Map<kotlin.String, V> = emptyMap()
//) {
//    init {
//        val r = validateArgs(args)
//        if (r is Validated.Fail) {
//            throw MuException(r.issues.joinToString("\n") { it })
//        }
//    }
//}
//
//sealed interface MuExpr {
//    data class Integer(val value: BigInteger) : MuExpr {
//        override fun toString(): kotlin.String = value.toString()
//    }
//    data class Double(val value: kotlin.Double) : MuExpr {
//        override fun toString(): kotlin.String = value.toString()
//    }
//    data class Rational(val value: wabbit.config.Rational) : MuExpr {
//        override fun toString(): kotlin.String = value.toString()
//    }
//    data class String(val value: kotlin.String) : MuExpr {
//        override fun toString(): kotlin.String {
//            return "\"$value\""
//        }
//    }
//    data class Atom(val value: kotlin.String) : MuExpr {
//        override fun toString(): kotlin.String {
//            return value
//        }
//    }
//    data class Seq(val value: List<MuExpr>) : MuExpr {
//        override fun toString(): kotlin.String {
//            return "(${value.joinToString(" ")})"
//        }
//    }
//}
//
//sealed interface MuFuncBody<V> {
//    data class Native<V>(val ptr: (Map<kotlin.String, V>, Map<kotlin.String, V>) -> V): MuFuncBody<V>
//    data class Expr<V>(val body: wabbit.config.MuExpr): MuFuncBody<V>
//}
//
//data class MuValue private constructor(val unsafeValue: Any?, val type: MuType) {
//    inline fun <reified A> extract(): A =
//        extract<A>(MuType.fromKType(typeOf<A>()))
//
//    inline fun <reified A : Any> extractOrNull(): A? =
//        extractOrNull<A>(MuType.fromKType(typeOf<A>()))
//
//    fun <A> extract(kClass: MuType): A {
//        if (unsafeValue == null) return unsafeValue as A
//        require(this.type == kClass) { "Value $unsafeValue is of type ${this.type}, expected $kClass" }
//        return unsafeValue as A
//    }
//
//    fun <A : Any> extractOrNull(kClass: MuType): A? {
//        if (unsafeValue == null) return unsafeValue
//        if (this.type == kClass) {
//            return unsafeValue as A
//        } else {
//            return null
//        }
//    }
//
//    companion object {
//        val nil = MuValue(null, MuType.nil)
//
//        inline fun <reified A> lift(value: A): MuValue {
//            val kType = typeOf<A>()
//            // if (value == null) return nil
//            return unsafeLift(value as Any?, kType)
//        }
//
//        fun <A> unsafeLift(value: A, kClass: KType): MuValue {
//            // When we lift a type, we need to make sure that it's a valid type.
//            // TODO
//            return MuValue(value, MuType.fromKType(kClass))
//        }
//
//        fun <A> unsafeLift(value: A, kClass: MuType): MuValue {
//            // When we lift a type, we need to make sure that it's a valid type.
//            // TODO
//            return MuValue(value, kClass)
//        }
//
//        fun integer(value: BigInteger): MuValue = lift(value)
//        fun double(value: kotlin.Double): MuValue = lift(value)
//        fun string(value: kotlin.String): MuValue = lift(value)
//        fun rational(value: Rational): MuValue = lift(value)
//        fun atom(value: MuExpr.Atom): MuValue = lift(value)
//        fun expr(value: MuExpr): MuValue = lift(value)
//        fun bool(value: kotlin.Boolean): MuValue = lift(value)
//        fun list(value: List<MuValue>): MuValue = lift(value)
//        fun set(value: Set<MuValue>): MuValue = lift(value)
//        fun func(value: MuFunc<MuValue>): MuValue = lift(value)
//
//        val cls = object : MuValueClass<MuValue> {
//            override fun integer(value: BigInteger): MuValue = MuValue.integer(value)
//            override fun double(value: Double): MuValue      = MuValue.double(value)
//            override fun rational(value: Rational): MuValue  = MuValue.rational(value)
//            override fun string(value: String): MuValue      = MuValue.string(value)
//            override fun atom(value: MuExpr.Atom): MuValue   = MuValue.atom(value)
//            override fun expr(value: MuExpr): MuValue        = MuValue.expr(value)
//            override fun extractFunc(value: MuValue): MuFunc<MuValue>? =
//                value.extractOrNull<MuFunc<MuValue>>()
//            override fun list(value: List<MuValue>): MuValue = MuValue.list(value)
//        }
//    }
//}
