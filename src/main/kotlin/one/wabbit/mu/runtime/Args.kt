package one.wabbit.mu.runtime

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import one.wabbit.data.*
import one.wabbit.mu.MuException

// Set to true to enable debug prints in matchArgs
private const val DEBUG_MATCH_ARGS = false

data class ArgArity(val minRequired: Int, val maxRequired: Int?) {
    init {
        require(minRequired >= 0) { "minRequired must be non-negative." }
        if (maxRequired != null) {
            require(maxRequired >= minRequired) { "maxRequired must be greater than or equal to minRequired." }
        }
    }
    // Properties derived from minRequired and maxRequired
    val isVararg: Boolean
        get() = maxRequired == null

    val isNullable: Boolean
        get() = minRequired == 0

    // override fun equals(other: Any?): Boolean = error("Use minRequired and maxRequired.")

    fun consume(consumed: Int): ArgArity {
        require(consumed >= 0) { "Argument $consumed must be positive." }
        if (consumed == 0) return this
        val newMinRequired = maxOf(0, minRequired - consumed)
        val newMaxRequired = if (maxRequired != null) {
            require(consumed <= maxRequired) { "Argument $consumed must be less than or equal to maxRequired." }
            maxRequired - consumed
        } else {
            null
        }
        return ArgArity(newMinRequired, newMaxRequired)
    }

    fun asSuffixString(): String {
        // Standard cases: required, ?, +, *, {n..m}
        if (minRequired == 1 && maxRequired == 1) {
            return ""
        } else if (minRequired == 0 && maxRequired == 1) {
            return "?"
        } else if (minRequired == 1 && maxRequired == null) {
            return "+"
        } else if (minRequired == 0 && maxRequired == null) {
            return "*"
        } else {
            if (maxRequired == null) {
                return "{$minRequired,}"
            } else {
                return "{$minRequired..$maxRequired}"
            }
        }
    }

    override fun toString(): String {
        return if (isVararg) {
            "[$minRequired..*]"
        } else {
            "[$minRequired..$maxRequired]"
        }
    }

    companion object {
        val Required = ArgArity(1, 1)
        val Optional = ArgArity(0, 1)
        val ZeroOrMore = ArgArity(0, null)
        val OneOrMore = ArgArity(1, null)
    }
}

// --- Argument Matching Exceptions ---
open class MatchArgsException(message: String) : MuException(message)
class TooManyArgumentsException(val values: List<*>) : MatchArgsException("Too many arguments provided. Extra: ${values.joinToString()}")
class MissingArgumentException(val names: List<*>) : MatchArgsException("Missing required argument: '$names'")
class DuplicateArgumentException(val name: Any) : MatchArgsException("Duplicate argument provided for: '$name'")
class PositionalArgumentsAfterOutOfOrderNamedArgumentException(val argValue: String, val argIndex: Int, val namedArgIndex: Int) : MatchArgsException("Positional argument '$argValue' (at index $argIndex) cannot follow an out-of-order named argument (last one at index $namedArgIndex).")
class UnknownArgumentException(val names: List<*>) : MatchArgsException("Unknown named argument: $names")
// class NotEnoughVarargArgumentsException(val name: String) : MatchArgsException("Not enough arguments provided for required vararg parameter: '$name'")
class NamedArgumentRequiresValueException(val names: List<*>) : MatchArgsException("Named argument $names requires at least one value.")
class AmbiguousPositionalArgumentException(
    val argValue: String? = null,
    val possibleParams: List<String>? = null,
    details: String = "Ambiguous assignment for positional arguments."
) : MatchArgsException(
    "$details" +
            (argValue?.let { " Argument: '$it'." } ?: "") +
            (possibleParams?.let { " Could belong to: ${it.joinToString()}." } ?: "")
)
class NamedArgumentValueExpectedException(val name: String, val unexpectedArg: String) : MatchArgsException("Named argument ':$name' expected a value, but got named argument ':$unexpectedArg'.")

// --- Core Matching Logic ---
sealed interface GoResult<out ArgumentName, out ArgumentValue> {
    val severity: Int

    data class Success<ArgumentName : Any, ArgumentValue : Any>(
        val result: Map<ArgumentName, List<ArgumentValue>>
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 0
    }

    data class Missing<ArgumentName : Any, ArgumentValue : Any>(
        val missing: List<Pair<ArgArity, ArgumentName>>
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 2
    }

    data class TooMany<ArgumentName : Any, ArgumentValue : Any>(
        val argName: ArgumentName,
        val extraValues: List<ArgumentValue>,
        val isDuplicate: Boolean
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 1
    }

    data class PositionalInNamedMode<ArgumentName : Any, ArgumentValue : Any>(
        val argValue: ArgumentValue,
        val dueToNamedOutOfOrder: Boolean
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 5
    }

    data class UnknownPositionalArgument<ArgumentName : Any, ArgumentValue : Any>(
        val argValue: ArgumentValue
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 5
    }

    data class Ambiguous<ArgumentName : Any, ArgumentValue : Any>(
        val argName: ArgumentName
    ) : GoResult<ArgumentName, ArgumentValue> {
        override val severity: Int = 10
    }
}

sealed interface ParameterPosition {
    fun incIfPositional(): ParameterPosition = when (this) {
        is ParameterPosition.Positional -> ParameterPosition.Positional(parameterIndex + 1)
        is ParameterPosition.Named -> this
    }

    data class Named(val dueToNamedOutOfOrder: Boolean) : ParameterPosition
    data class Positional(val parameterIndex: Int) : ParameterPosition
}

data class ConsumptionMode(val min: Int, val greedy: Boolean)

/**
 * Main entry point for argument matching. Orchestrates the phases.
 */
fun <ArgumentName : Any, ArgumentValue : Any> matchArgs(
    parameters: List<Pair<ArgArity, ArgumentName>>,
    arguments: List<Either<ArgumentName, ArgumentValue>>
): Map<ArgumentName, List<ArgumentValue>> {

    require(parameters.map { it.second }.toSet().size == parameters.size) {
        "Duplicate parameter names found: ${parameters.map { it.second }}"
    }
    // A1: We can assume that all parameter names are unique.

    if (DEBUG_MATCH_ARGS) {
        val paramString = parameters.mapIndexed { i, p -> "${p.second}${p.first.asSuffixString()}" }.joinToString(" ")
        val argString = arguments.mapIndexed { i, a -> if (a is Left) ":${a.value}" else "${(a as Right).value}" }.joinToString(" ")
        println("\nmatchArgs($paramString, $argString)")
    }

    val paramToArity = parameters.associate { it.second to it.first }

    // Step 1. First of all, we'll check if all named arguments are actually valid.
    val invalidNamedArgs = arguments.filterIsInstance<Left<ArgumentName>>()
        .filter { it.value !in paramToArity }
        .map { it.value }
    if (invalidNamedArgs.isNotEmpty()) {
        throw UnknownArgumentException(invalidNamedArgs)
    }
    // A2: We can assume that all names are valid.

    // Step 2. Check that there are no empty named arguments ("... :x :y ...") or
    //         dangling named arguments ("... :z" at the end).
    val emptyNamedArgs = arguments.mapIndexedNotNull { i, arg ->
        if (arg !is Left<ArgumentName>) {
            return@mapIndexedNotNull  null
        }
        if (i + 1 >= arguments.size || arguments[i + 1] is Left<*>) {
            return@mapIndexedNotNull arg.value
        } else {
            return@mapIndexedNotNull null
        }
    }
    if (emptyNamedArgs.isNotEmpty()) {
        throw NamedArgumentRequiresValueException(emptyNamedArgs)
    }
    // A3: We can assume that every named argument is followed by at least one value.

    fun go(
        depth: Int,
        position: ParameterPosition,
        argumentIndex: Int,
        result: PersistentMap<ArgumentName, PersistentList<ArgumentValue>>
    ): GoResult<ArgumentName, ArgumentValue> {
        check(depth <= parameters.size + arguments.size) {
            "Recursion depth exceeded: $depth > ${parameters.size + arguments.size}"
        }

        fun debug(message: String) {
            if (DEBUG_MATCH_ARGS) {
                println("  ${"  ".repeat(depth)}$message")
            }
        }

        debug("go($position, $argumentIndex, $result)")

        fun consume(argPosition: ParameterPosition, argParamName: ArgumentName, mode: ConsumptionMode, skipArgs: Int, newValues: List<ArgumentValue>): GoResult<ArgumentName, ArgumentValue> {
            debug("consume($argPosition, $argParamName, $mode, $skipArgs, $newValues)")
            // Named arg but in the expected position: doesn't force named arg mode.
            val currentValueList = result[argParamName]!!
            val paramArity = paramToArity[argParamName]!! // by A2
            val effectiveArity = paramArity.consume(currentValueList.size)

            val newValueCount = newValues.size // by A3 this is at least 1

            if (effectiveArity.maxRequired == 0) {
                // Param is satisfied, but we have extra arguments.
                // We explicitly distinguish 2 cases here:
                // If the parameter originally had 1 max arity, then it's a "duplicate".
                // Otherwise, it's a "too many" case.
                return GoResult.TooMany(argParamName, newValues, paramArity.maxRequired == 1)
            } else {
                // We potentially have exponentially many cases here.
                // But we can always terminate as long as find >= 2 successful cases and
                // report it as "ambiguous".
                var minConsumed = maxOf(mode.min, minOf(effectiveArity.minRequired, newValueCount))
                val maxConsumable = if (effectiveArity.maxRequired != null) {
                    minOf(effectiveArity.maxRequired, newValueCount)
                } else {
                    newValueCount
                }
                if (mode.greedy) {
                    // We can consume all values.
                    minConsumed = maxOf(minConsumed, maxConsumable)
                }
                debug("minConsumed: $minConsumed, maxConsumable: $maxConsumable")

                var best: GoResult<ArgumentName, ArgumentValue>? = null

                fun consider(case: GoResult<ArgumentName, ArgumentValue>): GoResult.Ambiguous<ArgumentName, ArgumentValue>? {
                    if (case is GoResult.Ambiguous) return case
                    if (best !is GoResult.Success) {
                        if (case is GoResult.Success) {
                            // First successful case.
                            debug("Found first successful case: $case")
                            best = case
                        } else {
                            if (best != null) {
                                if (best!!.severity < case.severity) {
                                    best = case
                                }
                            } else {
                                best = case
                            }
                        }
                    } else {
                        if (case is GoResult.Success && case != best) {
                            // We have a second successful case, we can report it as ambiguous.
                            debug("Found second successful case: $case")
                            return GoResult.Ambiguous(argParamName)
                        }
                    }
                    return null
                }

                for (i in minConsumed..maxConsumable) {
                    val finalArity = effectiveArity.consume(i)

                    val case = go(
                        depth + 1,
                        argPosition.incIfPositional(),
                        argumentIndex + skipArgs + i,
                        result.put(argParamName, currentValueList.addAll(newValues.take(i)))
                    )
                    val r = consider(case)
                    if (r != null) {
                        return r
                    }

                    if (finalArity.maxRequired != 0 && i > 0) {
                        // We can consume more values.
                        val case2 = go(
                            depth + 1,
                            argPosition,
                            argumentIndex + skipArgs + i,
                            result.put(argParamName, currentValueList.addAll(newValues.take(i)))
                        )
                        val r2 = consider(case2)
                        if (r2 != null) {
                            return r2
                        }
                    }
                }

                check(best != null) {
                    "We should have found at least one case (whether successful or not)."
                }

                return best!!
            }
        }

        if (argumentIndex >= arguments.size) {
            // There are still parameters left to process.
            val missingParams = parameters.filter {
                it.first.consume(result[it.second]!!.size).minRequired > 0
            }
            if (missingParams.isNotEmpty()) {
                return GoResult.Missing(missingParams)
            }
            return GoResult.Success(result)
        }

        if (position is ParameterPosition.Positional && position.parameterIndex >= parameters.size) {
            // There is at least one more argument but no more positional parameters.
            // We just switched to "named argument mode".
            debug("Switching to named argument mode.")
            return go(depth + 1, ParameterPosition.Named(dueToNamedOutOfOrder = false), argumentIndex, result)
        }

        when (val arg = arguments[argumentIndex]) {
            is Right -> {
                when (position) {
                    is ParameterPosition.Positional -> {
                        val (_, paramName) = parameters[position.parameterIndex]
                        debug("Positional argument: $arg")
                        return consume(
                            argPosition = position, argParamName = paramName,
                            mode = ConsumptionMode(0, false), skipArgs = 0,
                            newValues = listOf(arg.value))
                    }
                    is ParameterPosition.Named -> {
                        debug("Positional argument in named mode: $arg")
                        return GoResult.PositionalInNamedMode(arg.value, position.dueToNamedOutOfOrder)
                    }
                }
            }
            is Left -> {
                val argParamName = arg.value
                val newValues = arguments.drop(argumentIndex + 1).takeWhile { it is Right<*> }
                    .map { (it as Right<ArgumentValue>).value }

                val hasFollowingNamedArg = arguments.drop(argumentIndex + 1).any { it is Left<*> }

                val greedy = true // hasFollowingNamedArg

                when (position) {
                    is ParameterPosition.Named -> {
                        debug("Named argument in named mode: $arg")
                        return consume(
                            argPosition = position, argParamName = argParamName,
                            mode = ConsumptionMode(1, greedy), skipArgs = 1,
                            newValues = newValues)
                    }
                    is ParameterPosition.Positional -> {
                        val (_, paramName) = parameters[position.parameterIndex]

                        if (argParamName == paramName) {
                            debug("Named argument in expected position: $arg")
                            return consume(
                                position, argParamName,
                                mode = ConsumptionMode(1, greedy), skipArgs = 1,
                                newValues)
                        } else {
                            // Named parameter is out of order. There are three cases to consider:
                            // 1. We skipped forward over optional parameters
                            // 2. We skipped forward over required parameters
                            // 3. We moved back to some previous parameter

                            val newParameterIndex = parameters.indexOfFirst { it.second == argParamName }
                            check(newParameterIndex != -1) // by A2, this should never happen.
                            check(newParameterIndex != position.parameterIndex) // since name != paramName and A1, this should never happen.

                            if (newParameterIndex < position.parameterIndex) {
                                // Case 3: We moved back to some previous parameter.
                                // This switches us to "named argument mode".
                                debug("Named argument in previous position: $arg")
                                return consume(
                                    argPosition = ParameterPosition.Named(dueToNamedOutOfOrder = true), argParamName,
                                    mode = ConsumptionMode(1, greedy), skipArgs = 1,
                                    newValues)
                            }

                            val allOptional = (position.parameterIndex until newParameterIndex).all {
                                // Note that we don't check EFFECTIVE arity here.
                                parameters[it].first.isNullable
                            }

                            if (allOptional) {
                                // Case 1: We skipped forward over optional parameters.
                                // This switches us to "named argument mode".
                                debug("Named argument (skipped optional): $arg")
                                return consume(
                                    argPosition = ParameterPosition.Positional(newParameterIndex), argParamName,
                                    mode = ConsumptionMode(1, greedy), skipArgs = 1,
                                    newValues)
                            } else {
                                // Case 2: We skipped forward over required parameters.
                                // This switches us to "named argument mode".
                                debug("Named argument (skipped required): $arg")
                                return consume(
                                    argPosition = ParameterPosition.Named(dueToNamedOutOfOrder = true), argParamName,
                                    mode = ConsumptionMode(1, greedy), skipArgs = 1,
                                    newValues)
                            }
                        }
                    }
                }
            }
        }
    }

    val initialMap = persistentMapOf(*parameters.map { it.second to persistentListOf<ArgumentValue>() }.toTypedArray())

    when (val result = go(
        depth = 0,
        position = ParameterPosition.Positional(0),
        argumentIndex = 0,
        result = initialMap
    )) {
        is GoResult.Success -> {
            if (DEBUG_MATCH_ARGS) println("  Result: ${result.result}")
            return result.result
        }
        is GoResult.Missing -> {
            if (DEBUG_MATCH_ARGS) println("  Result: Missing ${result.missing.joinToString()}")
            throw MissingArgumentException(result.missing.map { it.second.toString() })
        }
        is GoResult.UnknownPositionalArgument -> {
            if (DEBUG_MATCH_ARGS) println("  Result: Unknown positional argument ${result.argValue}")
            throw UnknownArgumentException(listOf(result.argValue.toString()))
        }
        is GoResult.TooMany -> {
            if (DEBUG_MATCH_ARGS) println("  Result: Too many ${result.argName} ${result.extraValues.joinToString()}")
            if (result.isDuplicate) {
                throw DuplicateArgumentException(result.argName.toString())
            }
            throw TooManyArgumentsException(result.extraValues.map { it.toString() })
        }
        is GoResult.Ambiguous -> {
            if (DEBUG_MATCH_ARGS) println("  Result: Ambiguous ${result.argName}")
            throw AmbiguousPositionalArgumentException(result.argName.toString())
        }
        is GoResult.PositionalInNamedMode -> {
            if (DEBUG_MATCH_ARGS) println("  Result: Positional in named mode ${result.argValue}")
            if (result.dueToNamedOutOfOrder) {
                throw PositionalArgumentsAfterOutOfOrderNamedArgumentException(result.argValue.toString(), 0, 0)
            } else {
                throw TooManyArgumentsException(listOf(result.argValue.toString()))
            }
        }
    }
}
