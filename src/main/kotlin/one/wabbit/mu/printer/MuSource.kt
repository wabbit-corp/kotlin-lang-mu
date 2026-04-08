package one.wabbit.mu.printer

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import one.wabbit.mu.parser.MuParsedExpr
import one.wabbit.mu.parser.Spanned
import one.wabbit.mu.serialization.MuTreeFormat
import one.wabbit.parsing.Pos
import one.wabbit.parsing.TextAndPosSpan

object MuSource {
    inline fun <reified T> encodeToString(
        value: T,
        indent: Int? = null,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String = encodeToString(
        serializer = serializersModule.serializer<T>(),
        value = value,
        indent = indent,
        maxLineLength = maxLineLength,
        firstPositionalFields = firstPositionalFields,
        singleFieldPositional = singleFieldPositional,
        preserveSpans = preserveSpans,
        serializersModule = serializersModule,
    )

    fun <T> encodeToString(
        serializer: SerializationStrategy<T>,
        value: T,
        indent: Int? = null,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String = formatter(indent, maxLineLength, preserveSpans).formatDocument(
        listOf(
            normalizeTypedTree(
                treeFormat(
                serializersModule = serializersModule,
                firstPositionalFields = firstPositionalFields,
                singleFieldPositional = singleFieldPositional,
                ).encodeToTree(serializer, value)
            )
        )
    )

    fun render(
        expr: MuParsedExpr,
        indent: Int? = null,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String = formatter(indent, maxLineLength, preserveSpans).formatDocument(listOf(expr))

    fun renderDocument(
        expressions: Iterable<MuParsedExpr>,
        indent: Int? = null,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String = formatter(indent, maxLineLength, preserveSpans).formatDocument(expressions.toList())

    inline fun <reified T> encodeManyToString(
        values: Iterable<T>,
        indent: Int? = null,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String = encodeManyToString(
        serializer = serializersModule.serializer<T>(),
        values = values,
        indent = indent,
        maxLineLength = maxLineLength,
        firstPositionalFields = firstPositionalFields,
        singleFieldPositional = singleFieldPositional,
        preserveSpans = preserveSpans,
        serializersModule = serializersModule,
    )

    fun <T> encodeManyToString(
        serializer: SerializationStrategy<T>,
        values: Iterable<T>,
        indent: Int? = null,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String {
        val treeFormat =
            treeFormat(
                serializersModule = serializersModule,
                firstPositionalFields = firstPositionalFields,
                singleFieldPositional = singleFieldPositional,
            )
        val expressions = values.map { normalizeTypedTree(treeFormat.encodeToTree(serializer, it)) }
        return formatter(indent, maxLineLength, preserveSpans).formatDocument(expressions)
    }

    inline fun <reified T> encodeToPrettyString(
        value: T,
        indent: Int = 2,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String = encodeToString(
        value = value,
        indent = indent,
        maxLineLength = maxLineLength,
        firstPositionalFields = firstPositionalFields,
        singleFieldPositional = singleFieldPositional,
        preserveSpans = preserveSpans,
        serializersModule = serializersModule,
    )

    fun renderPretty(
        expr: MuParsedExpr,
        indent: Int = 2,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String = render(expr = expr, indent = indent, maxLineLength = maxLineLength, preserveSpans = preserveSpans)

    fun renderPrettyDocument(
        expressions: Iterable<MuParsedExpr>,
        indent: Int = 2,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String =
        renderDocument(
            expressions = expressions,
            indent = indent,
            maxLineLength = maxLineLength,
            preserveSpans = preserveSpans,
        )

    inline fun <reified T> encodeManyToPrettyString(
        values: Iterable<T>,
        indent: Int = 2,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String =
        encodeManyToString(
            values = values,
            indent = indent,
            maxLineLength = maxLineLength,
            firstPositionalFields = firstPositionalFields,
            singleFieldPositional = singleFieldPositional,
            preserveSpans = preserveSpans,
            serializersModule = serializersModule,
        )

    inline fun <reified T> encodeToCompactString(
        value: T,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String = encodeToString(
        value = value,
        indent = null,
        maxLineLength = maxLineLength,
        firstPositionalFields = firstPositionalFields,
        singleFieldPositional = singleFieldPositional,
        preserveSpans = preserveSpans,
        serializersModule = serializersModule,
    )

    fun renderCompact(
        expr: MuParsedExpr,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String = render(expr = expr, indent = null, maxLineLength = maxLineLength, preserveSpans = preserveSpans)

    fun renderCompactDocument(
        expressions: Iterable<MuParsedExpr>,
        maxLineLength: Int = 88,
        preserveSpans: Boolean = true,
    ): String = renderDocument(expressions = expressions, indent = null, maxLineLength = maxLineLength, preserveSpans = preserveSpans)

    inline fun <reified T> encodeManyToCompactString(
        values: Iterable<T>,
        maxLineLength: Int = 88,
        firstPositionalFields: Iterable<String> = emptyList(),
        singleFieldPositional: Boolean = true,
        preserveSpans: Boolean = true,
        serializersModule: SerializersModule = SerializersModule {},
    ): String =
        encodeManyToString(
            values = values,
            indent = null,
            maxLineLength = maxLineLength,
            firstPositionalFields = firstPositionalFields,
            singleFieldPositional = singleFieldPositional,
            preserveSpans = preserveSpans,
            serializersModule = serializersModule,
        )
}

private fun treeFormat(
    serializersModule: SerializersModule,
    firstPositionalFields: Iterable<String>,
    singleFieldPositional: Boolean,
): MuTreeFormat =
    MuTreeFormat(
        serializersModule = serializersModule,
        firstPositionalFields = firstPositionalFields.toSet(),
        singleFieldPositional = singleFieldPositional,
    )

private fun formatter(indent: Int?, maxLineLength: Int, preserveSpans: Boolean): MuSourceFormatter {
    require(maxLineLength >= 20) { "maxLineLength must be >= 20." }
    if (indent != null) {
        require(indent > 0) { "indent must be > 0 when provided." }
    }
    return MuSourceFormatter(pretty = indent != null, indent = indent ?: 2, maxLineLength = maxLineLength, preserveSpans = preserveSpans)
}

private enum class MuValueContext {
    Field,
    Sequence,
    MapKey,
    MapValue,
}

private fun normalizeTypedTree(
    expr: MuParsedExpr,
    context: MuValueContext = MuValueContext.Field,
): MuParsedExpr =
    when (expr) {
        is MuParsedExpr.String ->
            if (context != MuValueContext.Field && isAtomText(expr.value.value)) {
                atomNode(expr.value.value)
            } else {
                expr
            }
        is MuParsedExpr.Seq ->
            MuParsedExpr.Seq(
                expr.value.mapIndexed { index, value ->
                    when {
                        index == 0 -> value
                        value is MuParsedExpr.Atom && value.name.value.startsWith(":") ->
                            atomNode(":${camelToKebab(value.name.value.drop(1))}")
                        else -> normalizeTypedTree(value, MuValueContext.Field)
                    }
                }
            )
        is MuParsedExpr.List ->
            MuParsedExpr.List(expr.value.map { normalizeTypedTree(it, MuValueContext.Sequence) })
        is MuParsedExpr.Map ->
            MuParsedExpr.Map(
                expr.value.map { (key, value) ->
                    normalizeTypedTree(key, MuValueContext.MapKey) to
                        normalizeTypedTree(value, MuValueContext.MapValue)
                }
            )
        else -> expr
    }

private class MuSourceFormatter(
    private val pretty: Boolean,
    private val indent: Int,
    private val maxLineLength: Int,
    private val preserveSpans: Boolean,
) {
    fun formatDocument(expressions: List<MuParsedExpr>): String {
        if (expressions.isEmpty()) {
            return ""
        }
        val rendered = expressions.map { formatExpr(it, level = 0) }
        return rendered.joinToString(if (pretty) "\n\n" else "\n")
    }

    private fun formatExpr(expr: MuParsedExpr, level: Int): String =
        if (pretty) {
            formatExprPretty(expr, level)
        } else {
            formatExprConcise(expr, level)
        }

    private fun formatExprConcise(expr: MuParsedExpr, level: Int): String {
        val inline = inlineExpr(expr)
        if (inline.length <= budget(level)) {
            return inline
        }
        return formatExprPretty(expr, level)
    }

    private fun formatExprPretty(expr: MuParsedExpr, level: Int): String {
        val inline = inlineExpr(expr)
        if (inline.length <= budget(level) && canInline(expr)) {
            return inline
        }

        return when (expr) {
            is MuParsedExpr.Seq -> formatGroup(expr, level)
            is MuParsedExpr.List -> formatList(expr, level)
            is MuParsedExpr.Map -> formatMap(expr, level)
            else -> inline
        }
    }

    private fun formatGroup(expr: MuParsedExpr.Seq, level: Int): String {
        if (expr.value.isEmpty()) {
            return "()"
        }

        val inline = inlineExpr(expr)
        if (inline.length <= budget(level)) {
            return inline
        }

        val head = expr.value.first()
        val args = expr.value.drop(1)
        val (positional, named) = parseNamedArgs(args)

        val startTokens = buildList {
            add(inlineExpr(head))
            addAll(positional.map { formatExprConcise(it, level) })
        }
        val lines = mutableListOf("(" + startTokens.joinToString(" "))
        for ((name, values) in named) {
            lines += indentLines(formatNamedField(name, values, level + 1), levels = 1)
        }
        lines += ")"
        return lines.joinToString("\n")
    }

    private fun formatNamedField(name: String, values: List<MuParsedExpr>, level: Int): List<String> {
        if (values.isEmpty()) {
            return listOf(":$name")
        }

        val rendered = values.map { formatExpr(it, level) }
        if (rendered.all { '\n' !in it }) {
            val candidate = ":$name ${rendered.joinToString(" ")}"
            if (candidate.length <= budget(level)) {
                return listOf(candidate)
            }
        }

        if (rendered.size == 1) {
            val lines = rendered.single().lines()
            return listOf(":$name ${lines.first()}") + lines.drop(1)
        }

        val result = mutableListOf(":$name")
        for (text in rendered) {
            result += indentLines(text.lines(), levels = 1)
        }
        return result
    }

    private fun formatList(expr: MuParsedExpr.List, level: Int): String {
        if (expr.value.isEmpty()) {
            return "[]"
        }

        val inline = inlineExpr(expr)
        if (inline.length <= budget(level) && canInlineList(expr)) {
            return inline
        }

        val lines = mutableListOf("[")
        for (value in expr.value) {
            lines += indentLines(formatExpr(value, level + 1).lines(), levels = 1)
        }
        lines += "]"
        return lines.joinToString("\n")
    }

    private fun formatMap(expr: MuParsedExpr.Map, level: Int): String {
        if (expr.value.isEmpty()) {
            return "{}"
        }

        val inline = inlineExpr(expr)
        if (inline.length <= budget(level) && canInlineMap(expr)) {
            return inline
        }

        val lines = mutableListOf("{")
        expr.value.forEachIndexed { index, (key, value) ->
            val suffix = if (index < expr.value.lastIndex) "," else ""
            val keyText = formatExprConcise(key, level + 1)
            val valueLines = formatExpr(value, level + 1).lines()
            if (valueLines.size == 1) {
                lines += "${indent(1)}$keyText: ${valueLines.single()}$suffix"
            } else {
                lines += "${indent(1)}$keyText: ${valueLines.first()}"
                lines += indentLines(valueLines.drop(1), levels = 1)
                if (suffix.isNotEmpty()) {
                    lines[lines.lastIndex] = lines.last() + suffix
                }
            }
        }
        lines += "}"
        return lines.joinToString("\n")
    }

    private fun parseNamedArgs(values: List<MuParsedExpr>): Pair<List<MuParsedExpr>, List<Pair<String, List<MuParsedExpr>>>> {
        val positional = mutableListOf<MuParsedExpr>()
        val named = mutableListOf<Pair<String, List<MuParsedExpr>>>()
        var currentName: String? = null
        val currentValues = mutableListOf<MuParsedExpr>()

        for (value in values) {
            if (value is MuParsedExpr.Atom && value.name.value.startsWith(":")) {
                if (currentName != null) {
                    named += currentName to currentValues.toList()
                }
                currentName = value.name.value.drop(1)
                currentValues.clear()
                continue
            }

            if (currentName == null) {
                positional += value
            } else {
                currentValues += value
            }
        }

        if (currentName != null) {
            named += currentName to currentValues.toList()
        }

        return positional to named
    }

    private fun canInline(expr: MuParsedExpr): Boolean =
        when (expr) {
            is MuParsedExpr.Map -> expr.value.size <= 1 || !pretty
            is MuParsedExpr.List -> canInlineList(expr)
            else -> true
        }

    private fun canInlineList(expr: MuParsedExpr.List): Boolean {
        if (expr.value.isEmpty()) {
            return true
        }
        if (expr.value.size > 8) {
            return false
        }
        if (!expr.value.all(::isInlineScalar)) {
            return false
        }
        val firstType = expr.value.first()::class
        return expr.value.all { it::class == firstType }
    }

    private fun canInlineMap(expr: MuParsedExpr.Map): Boolean {
        if (expr.value.size <= 1) {
            return true
        }
        return !pretty
    }

    private fun isInlineScalar(expr: MuParsedExpr): Boolean =
        expr is MuParsedExpr.Atom ||
            expr is MuParsedExpr.String ||
            expr is MuParsedExpr.Integer ||
            expr is MuParsedExpr.Real ||
            expr is MuParsedExpr.Rational

    private fun budget(level: Int): Int = maxOf(20, maxLineLength - (indent * level))

    private fun inlineExpr(expr: MuParsedExpr): String =
        when (expr) {
            is MuParsedExpr.Atom -> if (preserveSpans) expr.name.span.raw else expr.name.value
            is MuParsedExpr.String -> quoteString(expr.value.value)
            is MuParsedExpr.Integer -> if (preserveSpans) expr.value.span.raw else expr.value.value.toString()
            is MuParsedExpr.Real -> if (preserveSpans) expr.value.span.raw else expr.value.value.toString()
            is MuParsedExpr.Rational ->
                if (preserveSpans) {
                    expr.value.span.raw
                } else {
                    "${expr.value.value.numerator}/${expr.value.value.denominator}"
                }
            is MuParsedExpr.Seq -> "(" + expr.value.joinToString(" ") { inlineExpr(it) } + ")"
            is MuParsedExpr.List -> "[" + expr.value.joinToString(" ") { inlineExpr(it) } + "]"
            is MuParsedExpr.Map ->
                "{" +
                    expr.value.joinToString(", ") { (key, value) ->
                        "${inlineExpr(key)}: ${inlineExpr(value)}"
                    } +
                    "}"
        }

    private fun indent(levels: Int): String = " ".repeat(indent * levels)

    private fun indentLines(lines: List<String>, levels: Int): List<String> {
        val prefix = indent(levels)
        return lines.map { if (it.isEmpty()) prefix else prefix + it }
    }
}

private fun quoteString(value: String): String =
    buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                '\u0000' -> append("\\0")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

private val ATOM_RE = Regex("^[^\\s()\\[\\]{}\\\",;]+$")

private fun isAtomText(value: String): Boolean = value.isNotEmpty() && ATOM_RE.matches(value)

private fun camelToKebab(value: String): String =
    buildString {
        for ((index, ch) in value.withIndex()) {
            when {
                ch == '_' -> append('-')
                ch.isUpperCase() && index > 0 -> {
                    append('-')
                    append(ch.lowercaseChar())
                }
                else -> append(ch.lowercaseChar())
            }
        }
    }

private fun atomNode(value: String): MuParsedExpr.Atom = MuParsedExpr.Atom(Spanned(value, spanFor(value)))

private fun spanFor(raw: String): TextAndPosSpan =
    TextAndPosSpan(raw = raw, start = Pos.start, end = Pos(1, raw.length.toLong() + 1, raw.length.toLong()))
