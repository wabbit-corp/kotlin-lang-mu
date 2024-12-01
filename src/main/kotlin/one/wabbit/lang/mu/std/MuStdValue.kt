package one.wabbit.lang.mu.std

import one.wabbit.lang.mu.Arg
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class MuStdValue private constructor(val unsafeValue: Any?, val type: MuType) {
    override fun toString(): String {
        return "$unsafeValue : $type"
    }

    companion object {
        inline fun <reified A> lift(value: A): MuStdValue {
            val kType = typeOf<A>()
            // if (value == null) return nil
            return unsafeLift(value as Any?, kType)
        }

        fun <A> unsafeLift(value: A, kClass: KType): MuStdValue {
            // When we lift a type, we need to make sure that it's a valid type.
            // TODO
            return MuStdValue(value, MuType.fromKType(kClass))
        }

        fun <A> unsafeLift(value: A, kClass: MuType): MuStdValue {
            // When we lift a type, we need to make sure that it's a valid type.
            // TODO
            return MuStdValue(value, kClass)
        }

        fun func(
            name: String?,
            typeParameters: List<TypeVariable>,
            parameters: List<Arg<MuStdValue>>,
            returnType: MuType,
            run: (MuStdContext, Map<String, MuStdValue>) -> Pair<MuStdContext, MuStdValue>
        ): MuStdValue = MuStdValue.unsafeLift(
            MuStdFunc(name, typeParameters, parameters, run),
            MuType.Func(typeParameters, parameters.map { it.type }, returnType)
        )

        val unit = MuStdValue.unsafeLift(Unit, MuType.Unit)
    }
}
