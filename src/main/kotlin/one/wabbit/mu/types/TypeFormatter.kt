package one.wabbit.mu.types

import one.wabbit.mu.runtime.MuStdContext
import one.wabbit.mu.types.MuType.* // Import MuType subclasses

/**
 * Formats MuType objects into human-readable strings.
 * Allows customization of how type constructor names are displayed.
 */
class TypeFormatter(private val rewrites: Map<String, RewrittenName>) {

    /**
     * Represents how a type constructor head should be formatted.
     */
    sealed interface RewrittenName {
        data class Postfix(val symbol: String) : RewrittenName
        data class Infix(val symbol: String) : RewrittenName
        data class Prefix(val symbol: String) : RewrittenName
        data class Name(val name: String) : RewrittenName
    }

    /**
     * Formats the given MuType into a string representation.
     *
     * @param type The MuType to format.
     * @return A string representation of the type.
     */
    fun format(type: MuType): String {
        return when (type) {
            is Constructor -> formatConstructor(type)
            is Forall -> formatForall(type)
            is Exists -> formatExists(type)
            is Use -> formatUse(type)
            is Func -> formatFunc(type)
        }
    }

    private fun formatConstructor(type: Constructor): String {
        // Handle the special nullable '?' constructor first
        if (type.head == "?") {
            val firstArg = type.args[0]
            require(type.args.size == 1) { "Nullable constructor '?' must have exactly one argument." }
            // Format the inner type and append '?'
            // Use parenthesis for precedence if inner type is complex (like Func or Forall)
            val innerFormatted = format(firstArg)
            val requiresParens = firstArg is Func || firstArg is Forall || (firstArg is Constructor && firstArg.head == "?") // Avoid double '??' without parens
            return if (requiresParens) "($innerFormatted)?" else "$innerFormatted?"
        }

        // Handle general constructors using rewrites
        val rewrite = rewrites[type.head] ?: RewrittenName.Name(type.head) // Default to Name if no rewrite found

        return when (rewrite) {
            is RewrittenName.Name -> {
                if (type.args.isEmpty()) rewrite.name
                else "${rewrite.name}[${type.args.joinToString(", ") { format(it) }}]"
            }
            is RewrittenName.Infix -> {
                check(type.args.size == 2) { "Infix operator '${rewrite.symbol}' requires 2 arguments, got ${type.args.size} for head '${type.head}'" }
                val firstArg = type.args[0]
                // Add parentheses for precedence if arguments are complex
                val left = formatParenthesized(firstArg, rewrite)
                val right = formatParenthesized(type.args[1], rewrite)
                "($left ${rewrite.symbol} $right)"
            }
            is RewrittenName.Postfix -> {
                check(type.args.size == 1) { "Postfix operator '${rewrite.symbol}' requires 1 argument, got ${type.args.size} for head '${type.head}'" }
                val firstArg = type.args[0]
                // Add parentheses for precedence
                val arg = formatParenthesized(firstArg, rewrite)
                "$arg${rewrite.symbol}"
            }
            is RewrittenName.Prefix -> {
                check(type.args.size == 1) { "Prefix operator '${rewrite.symbol}' requires 1 argument, got ${type.args.size} for head '${type.head}'" }
                val firstArg = type.args[0]
                // Add parentheses for precedence
                val arg = formatParenthesized(firstArg, rewrite)
                "${rewrite.symbol}$arg"
            }
        }
    }

    // Helper to add parentheses around complex types for operator precedence
    private fun formatParenthesized(type: MuType, operatorRewrite: RewrittenName): String {
        val formatted = format(type)
        // Add parentheses if the type is a Func, Forall, Exists, or an Infix/Nullable Constructor
        val needsParens = when(type) {
            is Func, is Forall, is Exists -> true
            is Constructor -> rewrites[type.head] is RewrittenName.Infix || type.head == "?"
            is Use -> false
        }
        return if (needsParens) "($formatted)" else formatted
    }


    private fun formatForall(type: Forall): String {
        return "forall ${type.vars.joinToString(", ")}. ${format(type.tpe)}"
    }

    private fun formatExists(type: Exists): String {
        return "exists ${type.vars.joinToString(", ")}. ${format(type.tpe)}"
    }

    private fun formatUse(type: Use): String {
        // Maybe add a prefix like '&' or keep it clean? Let's keep it clean.
        return type.name.name
    }

    private fun formatFunc(type: Func): String {
        val paramsFormatted = type.parameters.joinToString(", ") { format(it) }
        val returnFormatted = format(type.returnType)
        val typeParamsFormatted = if (type.typeParameters.isEmpty()) {
            ""
        } else {
            "[${type.typeParameters.joinToString(", ")}]"
        }
        return "$typeParamsFormatted($paramsFormatted) -> $returnFormatted"
    }

    companion object {
        // Default rewrites, moved from MuType
        val DEFAULT_REWRITES: Map<String, RewrittenName> = mapOf(
            // --- Standard Kotlin Types ---
            "kotlin.Nothing" to RewrittenName.Name("⊥"),
            "java.lang.Void" to RewrittenName.Name("⊥"), // Include java.lang.Void mapping
            "kotlin.Any" to RewrittenName.Name("⊤"),
            "kotlin.Int" to RewrittenName.Name("Int"),
            "kotlin.Double" to RewrittenName.Name("Double"),
            "kotlin.String" to RewrittenName.Name("String"),
            "kotlin.Boolean" to RewrittenName.Name("Boolean"),
            "kotlin.Unit" to RewrittenName.Name("Unit"),
            "java.math.BigInteger" to RewrittenName.Name("BigInteger"),
            "kotlin.collections.List" to RewrittenName.Name("List"),
            "kotlin.collections.Set" to RewrittenName.Name("Set"),
            "kotlin.collections.Map" to RewrittenName.Name("Map"),
            "kotlin.Pair" to RewrittenName.Name("Pair"), // Added Pair

            // --- Mu Specific Types ---
            MuLiteralInt::class.qualifiedName!! to RewrittenName.Name("LitInt"),
            MuLiteralString::class.qualifiedName!! to RewrittenName.Name("LitString"), // Added LitString
            MuType::class.qualifiedName!! to RewrittenName.Name("Type"), // Added Type itself
            MuStdContext::class.qualifiedName!! to RewrittenName.Name("Context"), // Added Context

            // --- Standard Library Type Classes / Concepts ---
            Upcast::class.qualifiedName!! to RewrittenName.Infix("<:<"),
            Eq::class.qualifiedName!! to RewrittenName.Infix("==="), // Added Eq example
            // Add other type classes like Monoid, Group if needed
            // "one.wabbit.mu.runtime.Monoid" to RewrittenName.Name("Monoid"),

            // --- Other Libraries ---
            "one.wabbit.math.Rational" to RewrittenName.Name("Rational"), // Use qualified name
            "one.wabbit.data.Union2" to RewrittenName.Infix("|"), // Use qualified name

            // --- Special Nullable Constructor ---
            // Handled specially in formatConstructor, but could be listed here for completeness
            // "?" to RewrittenName.Postfix("?"), // This rewrite isn't directly used due to special handling
        )

        /**
         * The default TypeFormatter instance using standard rewrites.
         */
        val default: TypeFormatter = TypeFormatter(DEFAULT_REWRITES)
    }
}
