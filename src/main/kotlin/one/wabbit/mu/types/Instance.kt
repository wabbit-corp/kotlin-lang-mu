package one.wabbit.mu.types

import one.wabbit.mu.types.TypeFormatter.Companion.default as F

data class Instance<V>(
    val typeParameters: List<TypeVariable>,
    val parameters: List<MuType.Constructor>,
    val returnType: MuType.Constructor,
    val get: (List<V>) -> V,
) {
    override fun toString(): String {
        return buildString {
            if (typeParameters.isNotEmpty()) {
                append("∀ ")
                append(typeParameters.joinToString(", "))
                append(". ")
            }
            if (parameters.isNotEmpty()) {
                append(parameters.joinToString(", ") { F.format(it) })
                append(" -> ")
            }
            append(F.format(returnType))
        }
    }
}
