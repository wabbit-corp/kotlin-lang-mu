package lang.mu

import one.wabbit.math.Rational
import one.wabbit.data.*
import java.math.BigInteger

fun capturedVars(expr: MuExpr): Set<String> {
    when (expr) {
        is MuExpr.Integer -> return emptySet()
        is MuExpr.Double -> return emptySet()
        is MuExpr.String -> return emptySet()
        is MuExpr.Rational -> return emptySet()

        is MuExpr.Atom -> {
            if (expr.value.startsWith("'")) {
                return emptySet()
            }
            return setOf(expr.value)
        }
        is MuExpr.Seq -> {
            val vars = mutableSetOf<String>()
            for (e in expr.value) {
                vars.addAll(capturedVars(e))
            }
            return vars
        }
    }
}

class UnboundVariableException(val name: String, val similar: List<String>): MuException("unbound variable: $name, did you mean: $similar")
class EmptyApplicationException: MuException("empty application")
class HeadIsNotAFunctionException(val expr: MuExpr, val value: Any?): MuException("head is not a function: $expr -> $value")

sealed class ResolutionError(message: String, cause: Throwable? = null) : MuException(message, cause) {
    data class UnknownModuleName(
        val moduleName: String, val similarNames: List<String>
    ) : ResolutionError("Unknown module name: $moduleName, similar names: $similarNames")
    data class UnboundVariable(
        val variableName: String, val similarNames: List<String>
    ) : ResolutionError("Unbound variable: $variableName, similar names: $similarNames")
}

interface InterpreterContext<Context, Value> {
    fun liftExpr(context: Context, value: MuExpr): Value

    fun liftInteger(context: Context, value: BigInteger): Value
    fun liftDouble(context: Context, value: Double): Value
    fun liftString(context: Context, value: String): Value
    fun liftRational(context: Context, value: Rational): Value
    fun liftList(context: Context, value: List<Value>): Value

    fun resolve(context: Context, name: String): Either<ResolutionError, Value>
    // fun resolveSimilar(context: Context, name: String, maxSize: Int): List<String>

    data class MuFunc<Context, Value>(
        val name: kotlin.String?,
        val args: List<Arg<Value>>,
        val run: (Context, Map<String, Value>) -> Pair<Context, Value>
    ) {
        init {
            val r = validateArgs(args)
            if (r is Validated.Fail) {
                throw MuException(r.issues.joinToString("\n") { it })
            }
        }
    }

    fun extractFunc(value: Value): MuFunc<Context, Value>?
}

fun <Context, Value> evaluateMu(context: Context, expr: String, tc: InterpreterContext<Context, Value>): Pair<Context, Value> {
    val parsed = MuParser.parse(expr)
    check(parsed.size == 1)
    return evaluateMu(context, parsed[0].lower(), tc)
}

fun <Context, Value> evaluateMu(context: Context, expr: MuExpr, tc: InterpreterContext<Context, Value>): Pair<Context, Value> {
    var ctx = context

    when (expr) {
        is MuExpr.Integer -> return ctx to tc.liftInteger(ctx, expr.value)
        is MuExpr.Double -> return ctx to tc.liftDouble(ctx, expr.value)
        is MuExpr.Rational -> return ctx to tc.liftRational(ctx, expr.value)
        is MuExpr.String -> return ctx to tc.liftString(ctx, expr.value)

        is MuExpr.Atom -> when (val resolvedValue = tc.resolve(ctx, expr.value)) {
            is Left -> throw resolvedValue.value
            is Right -> return ctx to resolvedValue.value
        }

        is MuExpr.Seq -> {
            if (expr.value.isEmpty()) {
                throw EmptyApplicationException()
            }

            val unevaluatedHead = expr.value[0]
            val headCtxAndValue = evaluateMu(ctx, unevaluatedHead, tc)
            ctx = headCtxAndValue.first
            val headValue = headCtxAndValue.second

            val function = tc.extractFunc(headValue) ?: throw HeadIsNotAFunctionException(expr.value[0], headValue)


            val argMap = try {
                matchArgs(
                    function.args.map { Pair(it.arity, it.name) },
                    expr.value.drop(1).map {
                        if (it is MuExpr.Atom && it.value.startsWith(":")) {
                            Left(it.value.drop(1))
                        } else {
                            Right(it)
                        }
                    }
                )
            } catch(e: UnknownArgumentException) {
                throw MuException("unknown argument: ${e.name} when matching arguments to ${unevaluatedHead}", e)
            }

            val argValues = mutableMapOf<String, Value>()
            for (arg in function.args) {
                val rawValues = argMap[arg.name] ?: emptyList()
                val values: MutableList<Value> = mutableListOf()
                if (arg.quote) {
                    rawValues.mapTo(values) { tc.liftExpr(ctx, it) }
                } else {
                    for (v in rawValues) {
                        val r0 = evaluateMu(ctx, v, tc)
                        ctx = r0.first
                        values.add(r0.second)
                    }
                }

                when (arg.arity) {
                    ArgArity.Required -> {
                        if (values.isEmpty()) {
                            throw MuException("missing required argument: ${arg.name}")
                        }
                        require(values.size == 1) { "impossible" }
                        argValues[arg.name] = values[0]
                    }

                    ArgArity.Optional -> {
                        if (!values.isEmpty()) {
                            argValues[arg.name] = values[0]
                        }
                    }

                    ArgArity.ZeroOrMore -> {
                        argValues[arg.name] = tc.liftList(ctx, values)
                    }

                    ArgArity.OneOrMore -> {
                        if (values.isEmpty()) {
                            throw MuException("missing required argument: ${arg.name}")
                        }
                        argValues[arg.name] = tc.liftList(ctx, values)
                    }
                }
            }

            return function.run(ctx, argValues)
        }
    }
}
