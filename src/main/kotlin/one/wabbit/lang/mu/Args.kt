package one.wabbit.lang.mu

import one.wabbit.lang.mu.std.MuType
import one.wabbit.data.*

enum class ArgArity {
    Required,
    Optional,
    ZeroOrMore,
    OneOrMore;

    val isNullable: Boolean get () = this == Optional || this == ZeroOrMore
    val isVararg: Boolean get () = this == ZeroOrMore || this == OneOrMore
}

data class Arg<V>(
    val name: kotlin.String,
    val quote: Boolean,
    val arity: ArgArity,
    val type: MuType
)

fun <V> validateArgs(list: List<Arg<V>>): Validated<String, List<Arg<V>>> = Validated.run {
    val uniqueNames = list.map { it.name }.toSet()
    if (uniqueNames.size != list.size) {
        raise("duplicate argument names")
    }
    failIfRaised()
    return@run list
}

class DuplicateArgumentException(val name: String) : MuException("duplicate argument: $name")
class UnknownArgumentException(val name: String) : MuException("unknown argument: $name")

fun <ArgumentName : Any, ArgumentValue : Any> matchArgs(
    parameters: List<Pair<ArgArity, ArgumentName>>,
    arguments: List<Either<ArgumentName, ArgumentValue>>
): Map<ArgumentName, List<ArgumentValue>> {
//    println(reprOf(parameters))
//    println(reprOf(arguments))

    val argQueue = ArrayDeque(arguments)
    var currentArgument = 0
    var namedOnly = false

    val arityByName = parameters.associate { it.second to it.first }

    val result = mutableMapOf<ArgumentName, MutableList<ArgumentValue>>()

    fun addArg(name: ArgumentName, value: ArgumentValue) {
        when (arityByName[name]) {
            ArgArity.Required, ArgArity.Optional -> {
                val list = result.getOrPut(name) { mutableListOf() }
                if (list.isNotEmpty()) {
                    throw DuplicateArgumentException(name.toString())
                }
                list.add(value)
            }
            ArgArity.ZeroOrMore, ArgArity.OneOrMore -> {
                result.getOrPut(name) { mutableListOf() }.add(value)
            }
            null -> error("impossible")
        }
    }

    val specialArgCount = parameters.count {
        it.first != ArgArity.Required
    }

    // There are 4 types of args: required, optional, zero-or-more, and one-or-more.

    while (true) {
        if (argQueue.isEmpty()) {
            break
        }

        val next = argQueue.removeFirst()

        when (next) {
            is Left -> {
                val name: ArgumentName = next.value
                val argIndex = parameters.indexOfFirst { it.second == name }
                if (argIndex == -1) {
                    throw UnknownArgumentException(name.toString())
                }
                val argDef = parameters[argIndex]

                // println("named arg: $name, currentArgument=$currentArgument, argDefs.size=${argDefs.size}")

                when (argDef.first) {
                    ArgArity.Required, ArgArity.Optional -> {
                        val value = argQueue.removeFirst()
                        if (value !is Right) {
                            throw MuException("argument name $value cannot be used as a value")
                        }
                        addArg(name, value.value)
                    }
                    ArgArity.ZeroOrMore, ArgArity.OneOrMore -> {
                        while (true) {
                            if (argQueue.isEmpty()) {
                                break
                            }

                            val value = argQueue.first()
                            if (value !is Right) {
                                break
                            }
                            argQueue.removeFirst()
                            addArg(name, value.value)
                        }
                    }
                }

                if (argIndex != currentArgument) {
                    namedOnly = true
                }
                currentArgument = argIndex + 1
            }

            is Right -> {
                // println("positional arg: ${next.value}, currentArgument=$currentArgument, argDefs.size=${argDefs.size}")

                if (namedOnly) {
                    throw MuException("positional argument after unordered named argument")
                }

                while (currentArgument < parameters.size - 1 && parameters[currentArgument].first.isNullable) {
                    currentArgument += 1
                }

                if (currentArgument >= parameters.size) {
                    throw MuException("too many arguments: ${next.value}")
                }

                val isLast = currentArgument == parameters.size - 1

                val argIndex = currentArgument
                val argDef = parameters[argIndex]
                val name = parameters[argIndex].second

                when (argDef.first) {
                    ArgArity.Required -> {
                        addArg(name, next.value)
                        currentArgument += 1
                    }
                    ArgArity.OneOrMore -> {
                        addArg(name, next.value)
                        if (isLast) {
                            while (true) {
                                if (argQueue.isEmpty()) {
                                    break
                                }

                                val value = argQueue.removeFirst()
                                if (value !is Right) {
                                    throw MuException("argument name $value cannot be used as a value")
                                }
                                addArg(name, value.value)
                            }
                        }
                        currentArgument += 1
                    }
                    ArgArity.Optional -> {
                        if (!isLast) error("impossible")
                        addArg(name, next.value)
                        currentArgument += 1
                    }
                    ArgArity.ZeroOrMore -> {
                        if (!isLast) error("impossible")
                        addArg(name, next.value)
                        while (true) {
                            if (argQueue.isEmpty()) {
                                break
                            }

                            val value = argQueue.removeFirst()
                            if (value !is Right) {
                                throw MuException("argument name $value cannot be used as a value")
                            }
                            addArg(name, value.value)
                        }
                    }
                }
            }
        }
    }

//    while (currentArgument < argDefs.size) {
//        val argDef = argDefs[currentArgument]
//        if (!argDef.first.isNullable) {
//            throw MuException("missing argument: ${argDef.second}")
//        }
//        currentArgument += 1
//    }

    for ((name, arity) in arityByName.entries) {
        val list = result[name]
        when (arity) {
            ArgArity.Required -> {
                if (list == null) {
                    throw MuException("missing required argument: $name")
                } else if (list.size == 0) {
                    // Shouldn't happen according to addArg's logic
                    error("impossible")
                } else if (list.size > 1) {
                    // Already handled by addArg
                    error("impossible")
                }
            }
            ArgArity.Optional -> {
                if (list == null) continue
                if (list.size > 1) {
                    // Already handled by addArg
                    error("impossible")
                }
            }
            ArgArity.ZeroOrMore -> { }
            ArgArity.OneOrMore -> {
                if (list == null) {
                    throw MuException("missing argument: $name")
                } else if (list.size == 0) {
                    // Shouldn't happen according to addArg's logic
                    error("impossible")
                }
            }
        }
    }

    return result
}
