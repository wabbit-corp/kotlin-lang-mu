package one.wabbit.mu.runtime

import one.wabbit.data.Validated
import one.wabbit.mu.types.MuType


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