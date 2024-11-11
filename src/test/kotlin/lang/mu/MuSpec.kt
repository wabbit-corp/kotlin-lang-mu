//package lang.mu
//
//import levenshtein.levenshtein
//import std.data.Cord
//import std.data.Left
//import std.data.Right
//import std.parsing.CharInput
//import wabbit.config.*
//import java.math.BigInteger
//import kotlin.reflect.*
//import kotlin.reflect.full.hasAnnotation
//import kotlin.test.Test
//
//private typealias KString = kotlin.String
//
//
//data class Arg(
//    val name: KString,
//    val quote: Boolean = false,
//    val arity: ArgArity = ArgArity.Required,
//    val implicit: Boolean = false,
//    val type: MuType,
//    val defaultValue: Any? = null)
//
//sealed interface MuType {
//    data class Forall(val names: List<String>, val type: MuType) : MuType
//    data class Use(val name: String) : MuType
//    data class Apply(val head: String, val args: List<MuType>) : MuType
//    data class Func(val args: List<Arg>, val result: MuType) : MuType
//
//    fun format(mayRequireParentheses: Boolean = false): String {
//        return when (this) {
//            is Forall -> {
//                val names = names.joinToString(", ")
//                "forall $names. ${type.format()}"
//            }
//
//            is Use -> name
//
//            is Apply -> {
//                if (head == "kotlin.Pair") {
//                    val arg1 = args[0].format()
//                    val arg2 = args[1].format()
//                    "($arg1, $arg2)"
//                } else if (head == "kotlin.Triple") {
//                    val arg1 = args[0].format()
//                    val arg2 = args[1].format()
//                    val arg3 = args[2].format()
//                    "($arg1, $arg2, $arg3)"
//                } else if (head == "kotlin.collections.List") {
//                    val arg1 = args[0].format()
//                    "[${arg1}]"
//                } else if (args.isEmpty()) {
//                    head
//                } else {
//                    val argsStr = args.joinToString(" ") { it.format(mayRequireParentheses = true) }
//                    if (mayRequireParentheses)
//                        "($head $argsStr)"
//                    else
//                        "$head $argsStr"
//                }
//            }
//
//            is Func -> {
//                val argsStr = args.joinToString(" -> ") {
//                    var argStr = it.name + " : " + it.type.format()
//                    when (it.arity) {
//                        ArgArity.Optional -> argStr = "$argStr?"
//                        ArgArity.ZeroOrMore -> argStr = "$argStr*"
//                        ArgArity.OneOrMore -> argStr = "$argStr+"
//                        else -> {}
//                    }
//                    if (it.implicit) {
//                        argStr = "{$argStr}"
//                    } else {
//                        argStr = "($argStr)"
//                    }
//                    argStr
//                }
//                val resultStr = result.format()
//                "$argsStr :-> $resultStr"
//            }
//        }
//    }
//
//    companion object {
//        fun fromKType(tpe: KType, ctx: Map<String, MuType> = emptyMap()): MuType {
//            val classifier = tpe.classifier
//            when (classifier) {
//                is KClass<*> -> {
//                    val name = tpe.classifier?.let { it as KClass<*> }?.qualifiedName!!
//
//                    val result = if (tpe.arguments.isNotEmpty()) {
//                        Apply(name, tpe.arguments.map { fromKType(it.type!!, ctx) })
//                    } else {
//                        Apply(name, emptyList())
//                    }
//
//                    if (tpe.isMarkedNullable) {
//                        return Apply("?", listOf(result))
//                    } else {
//                        return result
//                    }
//                }
//
//                is KTypeParameter -> {
//                    // println("Type parameter: ${classifier.name}: ${ctx}")
//                    return ctx[classifier.name]!!
//                }
//
//                else -> error("Unsupported type: $tpe")
//            }
//        }
//
//        fun fromKFunction(func: KFunction<*>, ctx: Map<String, MuType> = emptyMap()): MuType {
//            // Add the function's parameters to the context
//
//            val newCtx = ctx.toMutableMap()
//            for (param in func.typeParameters) {
//                val name = param.name
//                val type = Use(name)
//                newCtx[name] = type
//            }
//
//            val args = func.parameters.mapNotNull { param ->
//                if (param.kind == KParameter.Kind.INSTANCE) {
//                    return@mapNotNull null
//                }
//
//                val name = param.name!!
//                val type = fromKType(param.type, newCtx)
//
//                val arity = if (param.isVararg) {
//                    ArgArity.ZeroOrMore
//                } else {
//                    if (param.type.isMarkedNullable) {
//                        ArgArity.Optional
//                    } else {
//                        ArgArity.Required
//                    }
//                }
//
//                Arg(name, arity = arity, type = type,
//                    implicit = param.hasAnnotation<Mu.Implicit>())
//            }
//
//            val result = fromKType(func.returnType, newCtx)
//            val funcType = Func(args, result)
//
//            if (func.typeParameters.isNotEmpty()) {
//                return Forall(func.typeParameters.map { it.name }, funcType)
//            } else {
//                return funcType
//            }
//        }
//    }
//}
//
//
//
//typealias MuValue = Any?
//
//interface MuEnv {
//
//}
//
//class MuContext {
//    class Module {
//        val definitions = mutableMapOf<String, Any?>()
//    }
//
//    val modules = mutableMapOf<String, Module>()
//
//
//
//
//    object tc {
//        fun integer(value: BigInteger): MuValue = value
//        fun double(value: Double): MuValue = value
//        fun string(value: String): MuValue = value
//        fun rational(value: Rational): MuValue = value
//        fun atom(value: MuExpr.Atom): MuValue = value
//
//        fun expr(value: MuExpr): MuValue = value
//    }
//
//    fun eval(env: MuEnv, expr: MuExpr): MuValue {
//        when (expr) {
//            is MuExpr.Integer  -> return expr.value
//            is MuExpr.Double   -> return expr.value
//            is MuExpr.Rational -> return expr.value
//            is MuExpr.String   -> return expr.value
//
//            is MuExpr.Atom -> {
//                val name = expr.value
//                if (name.startsWith("'")) {
//                    return MuExpr.Atom(name.substring(1))
//                }
//
//                val v = env[name]
//                if (v == null) {
//                    val similarNames = env.keys.mapNotNull {
//                        val d = levenshtein(it, name)
//                        if (d >= 5) null
//                        else it to d
//                    }.sortedBy { it.second }.take(5).map { it.first }
//
//                    throw UnboundVariableException(name, similarNames)
//                }
//                return v
//            }
//
//            is MuExpr.Seq -> {
//                if (expr.value.isEmpty()) {
//                    throw EmptyApplicationException()
//                }
//
//                val head = eval(env, expr.value[0])
//
//                if (head !is Mu.Function) {
//                    throw HeadIsNotAFunctionException(expr.value[0])
//                }
//
//                var type = head.type
//                if (type is MuType.Forall) {
//                    type = type.type
//                }
//
//                check(type is MuType.Func) { "Expected function type, got $type" }
//
//                val argMap = matchArgs(
//                    type.args.map { Pair(it.arity, it.name) },
//                    expr.value.drop(1).map {
//                        if (it is MuExpr.Atom && it.value.startsWith(":")) {
//                            Left(it.value.drop(1))
//                        } else {
//                            Right(it)
//                        }
//                    }
//                )
//
//                val newEnv = function.capturedEnv.toMutableMap()
//
//                for (arg in type.args) {
//                    var rawValues = argMap[arg.name] ?: emptyList()
//                    val values: List<Any?>
//                    if (arg.quote) {
//                        values = rawValues.map { tc.expr(it) }
//                    } else {
//                        values = rawValues.map { eval(env, it) }
//                    }
//
//                    when (arg.arity) {
//                        ArgArity.Required -> {
//                            if (values.isEmpty()) {
//                                throw MuException("missing required argument: ${arg.name}")
//                            }
//                            require(values.size == 1) { "impossible" }
//                            newEnv[arg.name] = values[0]
//                        }
//
//                        ArgArity.Optional -> {
//                            if (values.isEmpty()) {
//                                newEnv[arg.name] = arg.defaultValue!!
//                            } else {
//                                newEnv[arg.name] = values[0]
//                            }
//                        }
//
//                        ArgArity.ZeroOrMore -> {
//                            newEnv[arg.name] = values
//                        }
//
//                        ArgArity.OneOrMore -> {
//                            if (values.isEmpty()) {
//                                throw MuException("missing required argument: ${arg.name}")
//                            }
//                            newEnv[arg.name] = values
//                        }
//                    }
//                }
//
//                return when (function.body) {
//                    is MuFuncBody.Expr ->
//                        runScript(newEnv, function.body.body, tc)
//                    is MuFuncBody.Native ->
//                        function.body.ptr(env, newEnv)
//                }
//            }
//        }
//    }
//
//    fun eval(expr: String): Any? {
//        val parser = MuParser(CharInput.of3(expr))
//        val parsed = parser.parseExpression()
//        return eval(parsed?.lower() ?: error("Failed to parse expression"))
//    }
//}
//
//class MuSpec {
//    @Test
//    fun test() {
//        val ctx = MuContext()
//        ctx.registerModule("test", TestObject())
//        ctx.eval("(+ 1 2.1)")
//    }
//}
