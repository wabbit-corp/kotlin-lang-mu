package one.wabbit.mu.types

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
                append(parameters.joinToString(", "))
                append(" -> ")
            }
            append(returnType)
        }
    }
}
