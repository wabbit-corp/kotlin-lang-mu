package one.wabbit.mu.runtime

import one.wabbit.mu.MuException
import one.wabbit.mu.types.MuType
import one.wabbit.mu.types.TypeFormatter
import one.wabbit.mu.types.TypeVariable
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.typeOf

data class MuStdValue private constructor(val unsafeValue: Any?, val type: MuType) {
    override fun toString(): String {
        return "$unsafeValue : ${TypeFormatter.default.format(type)}"
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
        ): MuStdValue = unsafeLift(
            MuStdFunc(name, typeParameters, parameters, run),
            MuType.Func(typeParameters, parameters.map { it.type }, returnType)
        )

        /**
         * Lifts a non-generic Kotlin KFunction into a MuStdValue (MuStdFunc).
         *
         * This provides a simpler way to expose Kotlin functions compared to full
         * module reflection with `withNativeModule`.
         *
         * Limitations:
         * - Does NOT support generic functions (KFunction with type parameters).
         * - Does NOT support receiver parameters (instance or extension). Function must be top-level or bound.
         * - Does NOT support parameters annotated with @Mu.Context or @Mu.Instance.
         * - Assumes all parameters are required and not quoted (@Mu.Quoted is ignored).
         * - Parameter names are required in the KFunction.
         * - Limited type checking at the boundary; relies mostly on Kotlin runtime checks.
         *
         * @param kfun The KFunction to lift.
         * @param name The name for the Mu function (defaults to KFunction name).
         * @param receiver The bound receiver instance if lifting an instance method (optional).
         * @return A MuStdValue wrapping a MuStdFunc.
         * @throws IllegalArgumentException if the function is generic, requires context/instance params,
         * or parameters lack names.
         */
        fun liftKFunction(
            kfun: KFunction<*>,
            name: String? = kfun.name,
            receiver: Any? = null // Pass instance if lifting a member function KFunction
        ): MuStdValue {
            // Limitation: No generics
            if (kfun.typeParameters.isNotEmpty()) {
                throw IllegalArgumentException("liftKFunction does not support generic functions: ${kfun.name}")
            }

            val muParams = mutableListOf<Arg<MuStdValue>>()
            val kotlinParamsData = mutableListOf<Pair<KParameter, MuType>>() // Store KParam and MuType

            for (p in kfun.parameters) {
                // Limitation: Skip receiver parameters
                if (p.kind == KParameter.Kind.INSTANCE || p.kind == KParameter.Kind.EXTENSION_RECEIVER) {
                    if (p.kind == KParameter.Kind.INSTANCE && receiver == null) {
                        throw IllegalArgumentException("Receiver parameter found but no receiver instance provided for function ${kfun.name}")
                    }
//                    if (p.kind == KParameter.Kind.INSTANCE && receiver != null && !p.type.classifier) {
//                        throw IllegalArgumentException("Provided receiver type mismatch for function ${kfun.name}")
//                    }
                    continue
                }

                // Limitation: Check for unsupported @Mu annotations/types
                if (p.type.classifier == MuStdContext::class || p.annotations.any { it is Mu.Instance }) {
                    throw IllegalArgumentException("liftKFunction cannot handle @Context or @Instance parameters: ${p.name} in ${kfun.name}. Use withNativeModule.")
                }
                // Basic check for other @Mu parameter annotations that are ignored
                if (p.annotations.any { it is Mu.Optional || it is Mu.ZeroOrMore || it is Mu.OneOrMore || it is Mu.Quoted || it is Mu.Name }) {
                    println("Warning: liftKFunction ignores @Optional, @ZeroOrMore, @OneOrMore, @Quoted, @Name annotations on parameter ${p.name} in ${kfun.name}.")
                }

                val pName = p.name ?: throw IllegalArgumentException("KFunction parameters must have names for lifting (function: ${kfun.name})")
                val pType = MuType.fromKType(p.type) // No bound vars needed as we disallowed generics

                // Assume Required, Not Quoted
                muParams.add(Arg(pName, false, ArgArity.Required, pType))
                kotlinParamsData.add(p to pType)
            }

            val returnMuType = MuType.fromKType(kfun.returnType)

            val runLambda: (MuStdContext, Map<String, MuStdValue>) -> Pair<MuStdContext, MuStdValue> = { ctx, argMap ->
                val callArgs = mutableListOf<Any?>()
                // Add receiver if present
                if (receiver != null) {
                    callArgs.add(receiver)
                }

                kotlinParamsData.forEach { (kParam, muType) ->
                    val muArg = argMap[kParam.name!!]
                        ?: throw MuException("Missing argument '${kParam.name!!}' for lifted function '$name'")

                    // Optional: Basic type compatibility check (can be improved)
                    // This is tricky because muArg.type might be a subtype, etc.
                    // Relying on kfun.call's runtime checks might be pragmatic here.
                    // if (!isTypeCompatible(muArg.type, muType)) {
                    //    throw MuException("Type mismatch for argument '${kParam.name!!}'. Expected compatible with $muType, got ${muArg.type}")
                    // }

                    callArgs.add(muArg.unsafeValue)
                }

                try {
                    // Use callBy for KFunctions >= 1.3.70 for potential parameter reordering resilience,
                    // but requires mapping KParameter -> Value. Sticking to positional call() for simplicity.
                    // val callResult = kfun.call(*callArgs.toTypedArray())
                    // Using callBy:
                    val callByMap = mutableMapOf<KParameter, Any?>()
                    kfun.parameters.forEachIndexed { index, kParam ->
                        // Assumes callArgs includes receiver at the start if needed
                        callByMap[kParam] = callArgs[index]
                    }
                    val callResult = kfun.callBy(callByMap)

                    // Wrap result. Need to handle Unit return type specifically?
                    // MuStdValue.unsafeLift should handle Unit fine.
                    ctx to unsafeLift(callResult, returnMuType)
                } catch (e: InvocationTargetException) {
                    throw e.targetException // Unwrap exception
                } catch (e: Exception) {
                    throw MuException("Error calling lifted Kotlin function '$name': ${e.message}", e)
                }
            }

            return func(name, emptyList(), muParams, returnMuType, runLambda)
        }

        val unit = unsafeLift(Unit, MuType.Unit)
    }
}
