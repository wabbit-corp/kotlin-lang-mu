@file:OptIn(ExperimentalSerializationApi::class)

package one.wabbit.mu.serialization

import java.io.File
import java.math.BigInteger
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.reflect.full.primaryConstructor
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import one.wabbit.data.Either
import one.wabbit.data.Left
import one.wabbit.data.Right
import one.wabbit.math.Rational
import one.wabbit.mu.parser.MuParsedExpr
import one.wabbit.mu.parser.MuParser
import one.wabbit.mu.parser.Spanned
import one.wabbit.mu.runtime.Mu
import one.wabbit.mu.runtime.ArgArity
import one.wabbit.mu.runtime.MatchArgsException
import one.wabbit.mu.runtime.matchArgs
import one.wabbit.parsing.Pos
import one.wabbit.parsing.TextAndPosSpan

private const val DEBUG_ENCODER = false
private const val DEBUG_DECODER = false

private val INTEGER_RE = Regex("^[+-]?[0-9](?:[0-9_]*[0-9])?$")
private val RATIONAL_RE =
    Regex("^(?<num>[+-]?[0-9](?:[0-9_]*[0-9])?)/(?<den>[0-9](?:[0-9_]*[0-9])?)$")
private val REAL_RE =
    Regex("^[+-]?[0-9](?:[0-9_]*[0-9])?\\.[0-9](?:[0-9_]*[0-9])?(?:[eE][+-]?[0-9]+)?$")
private val REAL_DOT_RE = Regex("^[+-]?[0-9](?:[0-9_]*[0-9])?\\.(?:[eE][+-]?[0-9]+)?$")
private val REAL_EXP_RE =
    Regex("^[+-]?[0-9](?:[0-9_]*[0-9])?(?:\\.[0-9](?:[0-9_]*[0-9])?)?[eE][+-]?[0-9]+$")
private val PERCENT_RE =
    Regex("^(?<value>[+-]?[0-9](?:[0-9_]*[0-9])?(?:\\.[0-9](?:[0-9_]*[0-9])?)?)%$")
private val NAME_EXTRA_CHARS = "_.@/+-$%=!?*#&~^|<>:'"

open class MuTreeFormat(
    override val serializersModule: SerializersModule = SerializersModule {},
    val classDiscriminator: String = "\$type",
    val polymorphicValueField: String = "\$value",
    val ignoreUnknownKeys: Boolean = false,
) : kotlinx.serialization.SerialFormat {
    init {
        require(classDiscriminator.isNotEmpty()) { "classDiscriminator must not be empty." }
        require(polymorphicValueField.isNotEmpty()) { "polymorphicValueField must not be empty." }
        require(classDiscriminator != polymorphicValueField) {
            "classDiscriminator and polymorphicValueField must be different."
        }
    }

    inline fun <reified T> encodeToTree(value: T): MuParsedExpr =
        encodeToTree(serializersModule.serializer<T>(), value)

    fun <T> encodeToTree(strategy: SerializationStrategy<T>, value: T): MuParsedExpr {
        val encoder = MuTreeEncoder(this, strategy)
        strategy.serialize(encoder, value)
        return encoder.result ?: error("Serializer ${strategy.descriptor.serialName} produced no Mu tree")
    }

    inline fun <reified T> decodeFromTree(tree: MuParsedExpr): T =
        decodeFromTree(serializersModule.serializer<T>(), tree)

    fun <T> decodeFromTree(strategy: DeserializationStrategy<T>, tree: MuParsedExpr): T {
        val decoder = MuTreeDecoder(this, tree, strategy)
        return strategy.deserialize(decoder)
    }

    inline fun <reified T> decodeFromString(source: String): T =
        decodeFromString(serializersModule.serializer<T>(), source)

    fun <T> decodeFromString(strategy: DeserializationStrategy<T>, source: String): T =
        decodeSingleParsed(strategy, MuParser.parse(source))

    inline fun <reified T> decodeManyFromString(source: String): List<T> =
        decodeManyFromString(serializersModule.serializer<T>(), source)

    fun <T> decodeManyFromString(
        strategy: DeserializationStrategy<T>,
        source: String,
    ): List<T> = decodeManyParsed(strategy, MuParser.parse(source))

    inline fun <reified T> decodeFromFile(
        file: File,
        charset: Charset = Charsets.UTF_8,
    ): T = decodeFromString(serializersModule.serializer<T>(), file.readText(charset))

    fun <T> decodeFromFile(
        strategy: DeserializationStrategy<T>,
        file: File,
        charset: Charset = Charsets.UTF_8,
    ): T = decodeFromString(strategy, file.readText(charset))

    inline fun <reified T> decodeManyFromFile(
        file: File,
        charset: Charset = Charsets.UTF_8,
    ): List<T> = decodeManyFromString(serializersModule.serializer<T>(), file.readText(charset))

    fun <T> decodeManyFromFile(
        strategy: DeserializationStrategy<T>,
        file: File,
        charset: Charset = Charsets.UTF_8,
    ): List<T> = decodeManyFromString(strategy, file.readText(charset))

    inline fun <reified T> decodeFromFile(
        path: Path,
        charset: Charset = Charsets.UTF_8,
    ): T = decodeFromFile(serializersModule.serializer<T>(), path.toFile(), charset)

    fun <T> decodeFromFile(
        strategy: DeserializationStrategy<T>,
        path: Path,
        charset: Charset = Charsets.UTF_8,
    ): T = decodeFromFile(strategy, path.toFile(), charset)

    inline fun <reified T> decodeManyFromFile(
        path: Path,
        charset: Charset = Charsets.UTF_8,
    ): List<T> = decodeManyFromFile(serializersModule.serializer<T>(), path.toFile(), charset)

    fun <T> decodeManyFromFile(
        strategy: DeserializationStrategy<T>,
        path: Path,
        charset: Charset = Charsets.UTF_8,
    ): List<T> = decodeManyFromFile(strategy, path.toFile(), charset)

    companion object Default : MuTreeFormat()
}

object MuTrees : MuTreeFormat()

private fun <T> MuTreeFormat.decodeSingleParsed(
    strategy: DeserializationStrategy<T>,
    expressions: List<MuParsedExpr>,
): T {
    if (expressions.size != 1) {
        throw SerializationException("Expected exactly one top-level expression, found ${expressions.size}.")
    }
    return decodeFromTree(strategy, expressions.single())
}

private fun <T> MuTreeFormat.decodeManyParsed(
    strategy: DeserializationStrategy<T>,
    expressions: List<MuParsedExpr>,
): List<T> =
    expressions.mapIndexed { index, expr ->
        try {
            decodeFromTree(strategy, expr)
        } catch (e: SerializationException) {
            throw SerializationException(
                "Failed to decode top-level expression at \$[$index]: ${e.message ?: "serialization error"}",
                e,
            )
        }
    }

private fun cleanNumeric(text: String): String = text.replace("_", "")

private fun Char.isMuNameChar(): Boolean = isLetterOrDigit() || this in NAME_EXTRA_CHARS

private fun looksNumericAtom(text: String): Boolean =
    INTEGER_RE.matches(text) ||
        RATIONAL_RE.matches(text) ||
        REAL_RE.matches(text) ||
        REAL_DOT_RE.matches(text) ||
        REAL_EXP_RE.matches(text) ||
        PERCENT_RE.matches(text)

private fun shouldRenderAsAtom(text: String): Boolean =
    text.isNotEmpty() && text.all(Char::isMuNameChar) && !looksNumericAtom(text)

private fun stringRaw(value: String): String =
    buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u0000' -> append("\\0")
                else -> append(ch)
            }
        }
        append('"')
    }

private fun spanFor(raw: String): TextAndPosSpan =
    TextAndPosSpan(raw = raw, start = Pos.start, end = Pos(1, raw.length.toLong() + 1, raw.length.toLong()))

private fun atomNode(value: String): MuParsedExpr.Atom = MuParsedExpr.Atom(Spanned(value, spanFor(value)))

private fun stringNode(value: String): MuParsedExpr.String =
    MuParsedExpr.String(Spanned(value, spanFor(stringRaw(value))))

private fun integerNode(value: BigInteger): MuParsedExpr.Integer =
    MuParsedExpr.Integer(Spanned(value, spanFor(value.toString())))

private fun realNode(value: Double): MuParsedExpr.Real =
    MuParsedExpr.Real(Spanned(value, spanFor(value.toString())))

private fun keyNode(value: String): MuParsedExpr =
    if (shouldRenderAsAtom(value)) {
        atomNode(value)
    } else {
        stringNode(value)
    }

private fun muStringLike(node: MuParsedExpr): String? =
    when (node) {
        is MuParsedExpr.Atom -> node.name.value
        is MuParsedExpr.String -> node.value.value
        else -> null
    }

private fun defaultMuTag(serialName: String): String {
    val simpleName = serialName.substringAfterLast('.').substringAfterLast('$')
    if (simpleName != serialName) {
        return buildString {
            for ((index, ch) in simpleName.withIndex()) {
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
    }
    return serialName
}

private fun classTag(descriptor: SerialDescriptor): String = defaultMuTag(descriptor.serialName)

private fun isNamedArgumentSafe(name: String): Boolean = shouldRenderAsAtom(":$name")

private fun isSingleFieldPositional(descriptor: SerialDescriptor): Boolean = descriptor.elementsCount == 1

private fun groupTag(node: MuParsedExpr.Seq): String? = node.value.firstOrNull()?.let(::muStringLike)

private fun elementArity(descriptor: SerialDescriptor, index: Int): ArgArity =
    if (descriptor.isElementOptional(index) || descriptor.getElementDescriptor(index).isNullable) {
        ArgArity.Optional
    } else {
        ArgArity.Required
    }

private fun groupTailToArguments(values: List<MuParsedExpr>): List<Either<String, MuParsedExpr>> =
    values.map { value ->
        if (value is MuParsedExpr.Atom && value.name.value.startsWith(":")) {
            Left(value.name.value.drop(1))
        } else {
            Right(value)
        }
    }

private fun decodeGroupFields(
    node: MuParsedExpr.Seq,
    descriptor: SerialDescriptor,
    layout: MuClassLayout,
    ignoreUnknownKeys: Boolean,
): Map<Int, MuParsedExpr> {
    val expectedTag = layout.tag
    val actualTag =
        groupTag(node)
            ?: throw SerializationException(
                "Expected tagged Mu group for ${descriptor.serialName}, found empty group: $node"
            )
    if (actualTag != expectedTag) {
        throw SerializationException(
            "Expected Mu tag '$expectedTag' for ${descriptor.serialName}, found '$actualTag'."
        )
    }

    val parameters =
        layout.fields.map { it.arity to it.muName }
    val filteredArgs =
        if (!ignoreUnknownKeys) {
            groupTailToArguments(node.value.drop(1))
        } else {
            val knownNames = parameters.map { it.second }.toSet()
            val raw = groupTailToArguments(node.value.drop(1))
            buildList {
                var index = 0
                while (index < raw.size) {
                    when (val arg = raw[index]) {
                        is Left -> {
                            if (arg.value in knownNames) {
                                add(arg)
                                index++
                            } else {
                                index++
                                while (index < raw.size && raw[index] is Right) {
                                    index++
                                }
                            }
                        }
                        is Right -> {
                            add(arg)
                            index++
                        }
                    }
                }
            }
        }
    val assigned =
        try {
            matchArgs(parameters, filteredArgs)
        } catch (e: MatchArgsException) {
            throw SerializationException(
                "Invalid Mu arguments for ${descriptor.serialName}: ${e.message ?: "argument mismatch"}",
                e,
            )
        }

    val result = LinkedHashMap<Int, MuParsedExpr>()
    for (field in layout.fields) {
        val values = assigned[field.muName].orEmpty()
        when {
            values.isEmpty() -> continue
            field.arity == ArgArity.ZeroOrMore || field.arity == ArgArity.OneOrMore ->
                result[field.index] = MuParsedExpr.List(values)
            values.size == 1 -> result[field.index] = values.single()
            else ->
                throw SerializationException(
                    "Expected a single Mu value for '${field.muName}', found ${values.size}."
                )
        }
    }
    return result
}

private data class MuFieldLayout(
    val index: Int,
    val sourceName: String,
    val muName: String,
    val arity: ArgArity,
)

private data class MuClassLayout(
    val tag: String,
    val fields: List<MuFieldLayout>,
)

private fun serializerTargetClass(serializer: Any?, descriptor: SerialDescriptor): Class<*>? {
    runCatching { Class.forName(descriptor.serialName) }.getOrNull()?.let { return it }

    val serializerClass = serializer?.javaClass ?: return null
    return listOfNotNull(serializerClass.enclosingClass, serializerClass.declaringClass).firstOrNull()
}

private fun resolveClassLayout(serializer: Any?, descriptor: SerialDescriptor): MuClassLayout {
    val targetClass = serializerTargetClass(serializer, descriptor)
    val constructorParams = targetClass?.kotlin?.primaryConstructor?.parameters.orEmpty()
    val classTag = targetClass?.getAnnotation(Mu.Tag::class.java)?.name ?: classTag(descriptor)

    val fields =
        (0 until descriptor.elementsCount).map { index ->
            val parameter = constructorParams.getOrNull(index)
            val muName =
                parameter?.annotations
                    ?.filterIsInstance<Mu.Name>()
                    ?.firstOrNull()
                    ?.name ?: descriptor.getElementName(index)
            val arity =
                when {
                    parameter?.annotations?.any { it is Mu.ZeroOrMore } == true -> ArgArity.ZeroOrMore
                    parameter?.annotations?.any { it is Mu.OneOrMore } == true -> ArgArity.OneOrMore
                    parameter?.annotations?.any { it is Mu.Optional } == true -> ArgArity.Optional
                    descriptor.isElementOptional(index) || descriptor.getElementDescriptor(index).isNullable ->
                        ArgArity.Optional
                    else -> ArgArity.Required
                }

            MuFieldLayout(
                index = index,
                sourceName = descriptor.getElementName(index),
                muName = muName,
                arity = arity,
            )
        }

    return MuClassLayout(tag = classTag, fields = fields)
}

private fun parseIntegerAtom(text: String): BigInteger? =
    if (INTEGER_RE.matches(text)) {
        cleanNumeric(text).toBigInteger()
    } else {
        null
    }

private fun parseRationalAtom(text: String): Rational? {
    val match = RATIONAL_RE.matchEntire(text) ?: return null
    val numerator = cleanNumeric(match.groups["num"]!!.value).toBigInteger()
    val denominator = cleanNumeric(match.groups["den"]!!.value).toBigInteger()
    if (denominator == BigInteger.ZERO) {
        return null
    }
    return Rational.parse("${numerator}/${denominator}")
}

private fun parseRealAtom(text: String): Double? {
    val percent = PERCENT_RE.matchEntire(text)
    if (percent != null) {
        return cleanNumeric(percent.groups["value"]!!.value).toDouble() / 100.0
    }
    if (REAL_RE.matches(text) || REAL_DOT_RE.matches(text) || REAL_EXP_RE.matches(text)) {
        return cleanNumeric(text).toDouble()
    }
    return null
}

private fun intValue(node: MuParsedExpr): BigInteger? =
    when (node) {
        is MuParsedExpr.Integer -> node.value.value
        is MuParsedExpr.Atom -> parseIntegerAtom(node.name.value)
        else -> null
    }

private fun doubleValue(node: MuParsedExpr): Double? =
    when (node) {
        is MuParsedExpr.Integer -> node.value.value.toDouble()
        is MuParsedExpr.Real -> node.value.value
        is MuParsedExpr.Rational -> node.value.value.toDouble()
        is MuParsedExpr.Atom -> parseRealAtom(node.name.value) ?: parseIntegerAtom(node.name.value)?.toDouble()
        else -> null
    }

private fun isNullNode(node: MuParsedExpr): Boolean =
    node is MuParsedExpr.Atom && node.name.value == "null"

private fun requireMapNode(node: MuParsedExpr, context: String): MuParsedExpr.Map =
    node as? MuParsedExpr.Map
        ?: throw SerializationException("Expected Mu map for $context, found ${node::class.simpleName}: $node")

private fun duplicateKeyError(name: String): Nothing =
    throw SerializationException("Duplicate Mu field '$name'.")

private class MuTreePolymorphicEncoder(private val parent: MuTreeEncoder) :
    MuTreeCompositeEncoder(parent.format.serializersModule), MuTreeElementOwner {
    override val format: MuTreeFormat
        get() = parent.format

    private var typeName: String? = null
    private var valueNode: MuParsedExpr? = null

    override fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr) {
        if (DEBUG_ENCODER) {
            println("MuTreePolymorphicEncoder.encodeElement($index, $value)")
        }
        when (index) {
            0 -> typeName = muStringLike(value)
                ?: throw SerializationException("Polymorphic discriminator must be a string-like Mu value, got $value")
            1 -> valueNode = value
            else -> error("Unexpected index $index for ${descriptor.kind}")
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val typeName = typeName ?: throw SerializationException("Missing polymorphic discriminator.")
        val valueNode = valueNode ?: throw SerializationException("Missing polymorphic value.")
        parent.publishResult(parent.format.wrapPolymorphic(typeName, valueNode))
    }
}

private sealed class MuTreeCompositeEncoder(
    final override val serializersModule: SerializersModule
) : CompositeEncoder {
    abstract fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr)

    override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) =
        encodeElement(descriptor, index, atomNode(if (value) "true" else "false"))

    override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) =
        encodeElement(descriptor, index, integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) =
        encodeElement(descriptor, index, integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) =
        encodeElement(descriptor, index, integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) =
        encodeElement(descriptor, index, integerNode(BigInteger.valueOf(value)))

    override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) =
        encodeElement(descriptor, index, realNode(value.toDouble()))

    override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) =
        encodeElement(descriptor, index, realNode(value))

    override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) =
        encodeElement(descriptor, index, stringNode(value.toString()))

    override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) =
        encodeElement(descriptor, index, stringNode(value))

    override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder =
        MuTreeEncoder(
            format = (this as? MuTreeElementOwner)?.format ?: error("Missing MuTreeFormat"),
            serializer = null,
            sink = { value -> encodeElement(descriptor, index, value) },
        )

    override fun <T> encodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        val format = (this as? MuTreeElementOwner)?.format ?: error("Missing MuTreeFormat")
        val encoder = MuTreeEncoder(format, serializer)
        serializer.serialize(encoder, value)
        encodeElement(descriptor, index, encoder.result ?: error("Missing encoded Mu element"))
    }

    override fun <T : Any> encodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        serializer: SerializationStrategy<T>,
        value: T?,
    ) {
        if (value == null) {
            encodeElement(descriptor, index, atomNode("null"))
            return
        }
        encodeSerializableElement(descriptor, index, serializer, value)
    }
}

private interface MuTreeElementOwner {
    val format: MuTreeFormat
}

private class MuTreeListEncoder(
    override val format: MuTreeFormat,
    private val parent: MuTreeEncoder,
) : MuTreeCompositeEncoder(format.serializersModule), MuTreeElementOwner {
    private val values = mutableListOf<MuParsedExpr>()

    override fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr) {
        values.add(value)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        parent.publishResult(MuParsedExpr.List(values))
    }
}

private class MuTreeMapEncoder(
    override val format: MuTreeFormat,
    private val parent: MuTreeEncoder,
) : MuTreeCompositeEncoder(format.serializersModule), MuTreeElementOwner {
    private val values = mutableListOf<Pair<MuParsedExpr, MuParsedExpr>>()
    private var lastKey: MuParsedExpr? = null

    override fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr) {
        if (index % 2 == 0) {
            lastKey = if (value is MuParsedExpr.String && shouldRenderAsAtom(value.value.value)) {
                atomNode(value.value.value)
            } else {
                value
            }
        } else {
            values += (lastKey ?: error("Missing Mu map key")) to value
            lastKey = null
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        parent.publishResult(MuParsedExpr.Map(values))
    }
}

private class MuTreeClassEncoder(
    override val format: MuTreeFormat,
    private val parent: MuTreeEncoder,
    private val layout: MuClassLayout,
) : MuTreeCompositeEncoder(format.serializersModule), MuTreeElementOwner {
    private val values = LinkedHashMap<Int, MuParsedExpr>()

    override fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr) {
        values[index] = value
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        val useGroup =
            isSingleFieldPositional(descriptor) || layout.fields.all { isNamedArgumentSafe(it.muName) }

        if (!useGroup) {
            parent.publishResult(
                MuParsedExpr.Map(
                    layout.fields.mapNotNull { field ->
                        values[field.index]?.let { keyNode(field.muName) to it }
                    }
                )
            )
            return
        }

        val exprs = mutableListOf<MuParsedExpr>(atomNode(layout.tag))
        for (field in layout.fields) {
            val rawValue = values[field.index] ?: continue

            if (field.arity == ArgArity.Optional && isNullNode(rawValue)) {
                continue
            }

            val encodedValues =
                if (field.arity == ArgArity.ZeroOrMore || field.arity == ArgArity.OneOrMore) {
                    val listValue =
                        rawValue as? MuParsedExpr.List
                            ?: throw SerializationException(
                                "Expected Mu list for variadic field '${field.muName}', found $rawValue"
                            )
                    if (field.arity == ArgArity.OneOrMore && listValue.value.isEmpty()) {
                        throw SerializationException("Field '${field.muName}' requires at least one value.")
                    }
                    if (listValue.value.isEmpty()) {
                        continue
                    }
                    listValue.value
                } else {
                    listOf(rawValue)
                }

            if (isSingleFieldPositional(descriptor)) {
                exprs += encodedValues
            } else {
                exprs += atomNode(":${field.muName}")
                exprs += encodedValues
            }
        }
        parent.publishResult(MuParsedExpr.Seq(exprs))
    }
}

private class MuTreeObjectEncoder(
    override val format: MuTreeFormat,
    private val parent: MuTreeEncoder,
    private val layout: MuClassLayout,
) : MuTreeCompositeEncoder(format.serializersModule), MuTreeElementOwner {
    override fun encodeElement(descriptor: SerialDescriptor, index: Int, value: MuParsedExpr) {
        error("Serializable objects do not have encodable elements.")
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        parent.publishResult(MuParsedExpr.Seq(listOf(atomNode(layout.tag))))
    }
}

private class MuTreeEncoder(
    val format: MuTreeFormat,
    val serializer: Any? = null,
    private val sink: ((MuParsedExpr) -> Unit)? = null,
) : Encoder {
    var result: MuParsedExpr? = null

    fun publishResult(value: MuParsedExpr) {
        result = value
        sink?.invoke(value)
    }

    override val serializersModule: SerializersModule = format.serializersModule

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        when (descriptor.kind) {
            StructureKind.CLASS -> MuTreeClassEncoder(format, this, resolveClassLayout(serializer, descriptor))
            StructureKind.LIST -> MuTreeListEncoder(format, this)
            StructureKind.MAP -> MuTreeMapEncoder(format, this)
            StructureKind.OBJECT -> MuTreeObjectEncoder(format, this, resolveClassLayout(serializer, descriptor))
            PolymorphicKind.SEALED,
            PolymorphicKind.OPEN -> MuTreePolymorphicEncoder(this)
            SerialKind.ENUM ->
                error("Enums are handled via encodeEnum/decodeEnum, not beginStructure.")
            is PrimitiveKind -> error("Primitive kinds do not begin structures.")
            SerialKind.CONTEXTUAL ->
                error(
                    "Contextual descriptor reached Mu tree encoder beginStructure. " +
                        "Ensure the correct SerializersModule is passed."
                )
        }

    override fun encodeBoolean(value: Boolean) = publishResult(atomNode(if (value) "true" else "false"))

    override fun encodeByte(value: Byte) = publishResult(integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeShort(value: Short) = publishResult(integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeInt(value: Int) = publishResult(integerNode(BigInteger.valueOf(value.toLong())))

    override fun encodeLong(value: Long) = publishResult(integerNode(BigInteger.valueOf(value)))

    override fun encodeFloat(value: Float) = publishResult(realNode(value.toDouble()))

    override fun encodeDouble(value: Double) = publishResult(realNode(value))

    override fun encodeChar(value: Char) = publishResult(stringNode(value.toString()))

    override fun encodeString(value: String) = publishResult(stringNode(value))

    override fun encodeNull() = publishResult(atomNode("null"))

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        publishResult(atomNode(enumDescriptor.getElementName(index)))
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder = this
}

private sealed class MuTreeCompositeDecoder(
    final override val serializersModule: SerializersModule
) : CompositeDecoder {
    abstract fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeBoolean()

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeByte()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeDouble()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeChar()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index)).decodeString()

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder =
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index))

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T = deserializer.deserialize(
        MuTreeDecoder((this as MuTreeElementOwner).format, decodeElementNode(descriptor, index), deserializer)
    )

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? {
        val node = decodeElementNode(descriptor, index)
        if (isNullNode(node)) {
            return null
        }
        return deserializer.deserialize(MuTreeDecoder((this as MuTreeElementOwner).format, node, deserializer))
    }
}

private class MuTreeListDecoder(
    override val format: MuTreeFormat,
    private val node: MuParsedExpr.List,
) : MuTreeCompositeDecoder(format.serializersModule), MuTreeElementOwner {
    private var cursor = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = node.value.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < node.value.size) {
            cursor++
            cursor - 1
        } else {
            CompositeDecoder.DECODE_DONE
        }

    override fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr =
        node.value[index]

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class MuTreeMapDecoder(
    override val format: MuTreeFormat,
    private val node: MuParsedExpr.Map,
) : MuTreeCompositeDecoder(format.serializersModule), MuTreeElementOwner {
    private var cursor = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = node.value.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < node.value.size * 2) {
            cursor++
            cursor - 1
        } else {
            CompositeDecoder.DECODE_DONE
        }

    override fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr {
        val pair = node.value[index / 2]
        return if (index % 2 == 0) pair.first else pair.second
    }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class MuTreeClassDecoder(
    override val format: MuTreeFormat,
    node: MuParsedExpr,
    private val descriptor: SerialDescriptor,
    private val layout: MuClassLayout,
) : MuTreeCompositeDecoder(format.serializersModule), MuTreeElementOwner {
    private val valueByIndex: Map<Int, MuParsedExpr>
    private val orderedIndices: List<Int>
    private var cursor = 0

    init {
        val byIndex = LinkedHashMap<Int, MuParsedExpr>()
        when (node) {
            is MuParsedExpr.Map -> {
                val indexByMuName = layout.fields.associateBy({ it.muName }, { it.index })
                for ((keyExpr, valueExpr) in node.value) {
                    val key =
                        muStringLike(keyExpr)
                            ?: throw SerializationException("Class field keys must be strings or atoms, got $keyExpr")
                    val index = indexByMuName[key] ?: CompositeDecoder.UNKNOWN_NAME
                    if (index == CompositeDecoder.UNKNOWN_NAME) {
                        if (!format.ignoreUnknownKeys) {
                            throw SerializationException(
                                "Unknown Mu field '$key' for ${descriptor.serialName}."
                            )
                        }
                        continue
                    }
                    if (byIndex.containsKey(index)) {
                        duplicateKeyError(key)
                    }
                    byIndex[index] = valueExpr
                }
            }
            is MuParsedExpr.Seq -> {
                byIndex.putAll(decodeGroupFields(node, descriptor, layout, format.ignoreUnknownKeys))
            }
            else ->
                throw SerializationException(
                    "Expected tagged Mu group or Mu map for ${descriptor.serialName}, found ${node::class.simpleName}: $node"
                )
        }
        valueByIndex = byIndex
        orderedIndices = valueByIndex.keys.toList()
    }

    override fun decodeSequentially(): Boolean = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < orderedIndices.size) {
            orderedIndices[cursor++]
        } else {
            CompositeDecoder.DECODE_DONE
        }

    override fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr =
        valueByIndex[index] ?: throw SerializationException("Missing Mu field ${descriptor.getElementName(index)}.")

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class MuTreeObjectDecoder(
    override val format: MuTreeFormat,
    node: MuParsedExpr,
    descriptor: SerialDescriptor,
    layout: MuClassLayout,
) : MuTreeCompositeDecoder(format.serializersModule), MuTreeElementOwner {
    init {
        when (node) {
            is MuParsedExpr.Map -> {
                if (!format.ignoreUnknownKeys && node.value.isNotEmpty()) {
                    val keyExpr = node.value.first().first
                    val key =
                        muStringLike(keyExpr)
                            ?: throw SerializationException("Object field keys must be strings or atoms, got $keyExpr")
                    throw SerializationException("Unknown Mu field '$key' for ${descriptor.serialName}.")
                }
            }
            is MuParsedExpr.Seq -> {
                val actualTag =
                    groupTag(node)
                        ?: throw SerializationException(
                            "Expected tagged Mu group for ${descriptor.serialName}, found empty group: $node"
                        )
                if (actualTag != layout.tag) {
                    throw SerializationException(
                        "Expected Mu tag '${layout.tag}' for ${descriptor.serialName}, found '$actualTag'."
                    )
                }
                if (!format.ignoreUnknownKeys && node.value.size != 1) {
                    throw SerializationException("Unknown Mu field for ${descriptor.serialName}.")
                }
            }
            else ->
                throw SerializationException(
                    "Expected tagged Mu group or Mu map for ${descriptor.serialName}, found ${node::class.simpleName}: $node"
                )
        }
    }

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE

    override fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr =
        error("Serializable objects have no decodable elements.")

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class MuTreePolymorphicDecoder(
    override val format: MuTreeFormat,
    private val typeName: String,
    private val valueNode: MuParsedExpr,
) : MuTreeCompositeDecoder(format.serializersModule), MuTreeElementOwner {
    private var cursor = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < 2) {
            cursor++
            cursor - 1
        } else {
            CompositeDecoder.DECODE_DONE
        }

    override fun decodeElementNode(descriptor: SerialDescriptor, index: Int): MuParsedExpr =
        when (index) {
            0 -> stringNode(typeName)
            1 -> valueNode
            else -> error("Unexpected index $index for ${descriptor.kind}")
        }

    override fun endStructure(descriptor: SerialDescriptor) {}
}

private class MuTreeDecoder(
    private val format: MuTreeFormat,
    private val node: MuParsedExpr,
    private val deserializer: Any? = null,
) : Decoder {
    override val serializersModule: SerializersModule = format.serializersModule

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.CLASS ->
                MuTreeClassDecoder(format, node, descriptor, resolveClassLayout(deserializer, descriptor))
            StructureKind.LIST ->
                MuTreeListDecoder(
                    format,
                    node as? MuParsedExpr.List
                        ?: throw SerializationException(
                            "Expected Mu list for ${descriptor.serialName}, found ${node::class.simpleName}: $node"
                        ),
                )
            StructureKind.MAP -> MuTreeMapDecoder(format, requireMapNode(node, descriptor.serialName))
            StructureKind.OBJECT -> {
                MuTreeObjectDecoder(format, node, descriptor, resolveClassLayout(deserializer, descriptor))
            }
            PolymorphicKind.SEALED,
            PolymorphicKind.OPEN -> {
                val (typeName, valueNode) = format.unwrapPolymorphic(node)
                MuTreePolymorphicDecoder(format, typeName, valueNode)
            }
            SerialKind.ENUM ->
                error("Enums are handled via encodeEnum/decodeEnum, not beginStructure.")
            is PrimitiveKind -> error("Primitive kinds do not begin structures.")
            SerialKind.CONTEXTUAL ->
                error(
                    "Contextual descriptor reached Mu tree decoder beginStructure. " +
                        "Ensure the correct SerializersModule is passed."
                )
        }

    override fun decodeNotNullMark(): Boolean = !isNullNode(node)

    override fun decodeNull(): Nothing? {
        if (isNullNode(node)) {
            return null
        }
        throw SerializationException("Expected Mu null atom, found $node")
    }

    override fun decodeBoolean(): Boolean {
        val value =
            when (node) {
                is MuParsedExpr.Atom -> node.name.value
                is MuParsedExpr.String -> node.value.value
                else -> throw SerializationException("Expected Mu boolean atom/string, found $node")
            }
        return when (value) {
            "true" -> true
            "false" -> false
            else -> throw SerializationException("Expected Mu boolean atom/string, found '$value'")
        }
    }

    override fun decodeByte(): Byte {
        val value = intValue(node) ?: throw SerializationException("Expected Mu integer, found $node")
        return try {
            value.toByteExact()
        } catch (e: ArithmeticException) {
            throw SerializationException("Mu integer $value does not fit in Byte.", e)
        }
    }

    override fun decodeShort(): Short {
        val value = intValue(node) ?: throw SerializationException("Expected Mu integer, found $node")
        return try {
            value.toShortExact()
        } catch (e: ArithmeticException) {
            throw SerializationException("Mu integer $value does not fit in Short.", e)
        }
    }

    override fun decodeInt(): Int {
        val value = intValue(node) ?: throw SerializationException("Expected Mu integer, found $node")
        return try {
            value.intValueExact()
        } catch (e: ArithmeticException) {
            throw SerializationException("Mu integer $value does not fit in Int.", e)
        }
    }

    override fun decodeLong(): Long {
        val value = intValue(node) ?: throw SerializationException("Expected Mu integer, found $node")
        return try {
            value.longValueExact()
        } catch (e: ArithmeticException) {
            throw SerializationException("Mu integer $value does not fit in Long.", e)
        }
    }

    override fun decodeFloat(): Float {
        val value = doubleValue(node) ?: throw SerializationException("Expected Mu real value, found $node")
        return value.toFloat()
    }

    override fun decodeDouble(): Double =
        doubleValue(node) ?: throw SerializationException("Expected Mu real value, found $node")

    override fun decodeChar(): Char {
        val value = muStringLike(node) ?: throw SerializationException("Expected Mu string-like value, found $node")
        if (value.length != 1) {
            throw SerializationException("Expected single-character Mu string, found '$value'")
        }
        return value[0]
    }

    override fun decodeString(): String =
        muStringLike(node) ?: throw SerializationException("Expected Mu string-like value, found $node")

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val name = muStringLike(node) ?: throw SerializationException("Expected Mu enum atom/string, found $node")
        return enumDescriptor.getElementIndex(name).let { index ->
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                throw SerializationException(
                    "Unknown enum value '$name' for ${enumDescriptor.serialName}."
                )
            }
            index
        }
    }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
}

private fun BigInteger.toByteExact(): Byte = byteValueExact()

private fun BigInteger.toShortExact(): Short = shortValueExact()

private fun MuTreeFormat.wrapPolymorphic(typeName: String, value: MuParsedExpr): MuParsedExpr =
    when (value) {
        is MuParsedExpr.Seq -> {
            if (groupTag(value) == typeName) {
                value
            } else {
                MuParsedExpr.Seq(listOf(atomNode(typeName), atomNode(":$polymorphicValueField"), value))
            }
        }
        else -> MuParsedExpr.Seq(listOf(atomNode(typeName), atomNode(":$polymorphicValueField"), value))
    }

private fun MuTreeFormat.unwrapPolymorphic(node: MuParsedExpr): Pair<String, MuParsedExpr> {
    if (node is MuParsedExpr.Seq) {
        val typeName =
            groupTag(node)
                ?: throw SerializationException("Expected tagged Mu group for polymorphic value, found $node")
        if (node.value.size >= 3 &&
            node.value[1] is MuParsedExpr.Atom &&
            (node.value[1] as MuParsedExpr.Atom).name.value == ":$polymorphicValueField"
        ) {
            if (node.value.size != 3) {
                throw SerializationException(
                    "Polymorphic value cannot mix group fields with reserved '$polymorphicValueField'."
                )
            }
            return typeName to node.value[2]
        }
        return typeName to node
    }

    val map = requireMapNode(node, "polymorphic value")
    var typeName: String? = null
    var explicitValue: MuParsedExpr? = null
    val flattenedEntries = mutableListOf<Pair<MuParsedExpr, MuParsedExpr>>()

    for ((key, value) in map.value) {
        when (muStringLike(key)) {
            classDiscriminator -> {
                if (typeName != null) {
                    duplicateKeyError(classDiscriminator)
                }
                typeName =
                    muStringLike(value)
                        ?: throw SerializationException(
                            "Polymorphic discriminator '$classDiscriminator' must be string-like, got $value"
                        )
            }
            polymorphicValueField -> {
                if (explicitValue != null) {
                    duplicateKeyError(polymorphicValueField)
                }
                explicitValue = value
            }
            else -> flattenedEntries += key to value
        }
    }

    val resolvedTypeName = typeName ?: throw SerializationException(
        "Missing polymorphic discriminator '$classDiscriminator'."
    )

    if (explicitValue != null && flattenedEntries.isNotEmpty()) {
        throw SerializationException(
            "Polymorphic value cannot mix flattened fields with reserved '$polymorphicValueField'."
        )
    }

    val payload = explicitValue ?: MuParsedExpr.Map(flattenedEntries)
    return resolvedTypeName to payload
}
