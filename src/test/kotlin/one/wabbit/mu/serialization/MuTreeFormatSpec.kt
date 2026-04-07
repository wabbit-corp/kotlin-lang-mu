package one.wabbit.mu.serialization

import java.util.UUID
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import one.wabbit.mu.parser.MuParsedExpr
import one.wabbit.mu.parser.MuParser
import one.wabbit.mu.runtime.Mu

private fun parseOne(source: String): MuParsedExpr = MuParser.parse(source).single()

private fun canonical(expr: MuParsedExpr): String =
    when (expr) {
        is MuParsedExpr.Integer -> "int(${expr.value.value})"
        is MuParsedExpr.Rational -> "rational(${expr.value.value})"
        is MuParsedExpr.Real -> "real(${expr.value.value})"
        is MuParsedExpr.Atom -> "atom(${expr.name.value})"
        is MuParsedExpr.String -> "string(${expr.value.value})"
        is MuParsedExpr.Seq -> expr.value.joinToString(prefix = "seq[", postfix = "]") { canonical(it) }
        is MuParsedExpr.List -> expr.value.joinToString(prefix = "list[", postfix = "]") { canonical(it) }
        is MuParsedExpr.Map ->
            expr.value.joinToString(prefix = "map[", postfix = "]") { (k, v) ->
                "${canonical(k)}=>${canonical(v)}"
            }
    }

private fun assertTreeEquals(expectedSource: String, actual: MuParsedExpr) {
    assertEquals(canonical(parseOne(expectedSource)), canonical(actual))
}

object UUIDAsString : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

@Serializable
data class Person(val name: String, val age: Int, val active: Boolean)

@Serializable
data class Inventory(val ids: List<Int>, val counts: Map<String, Int>)

@Serializable
data class MaybeValue(val present: String?, val fallback: String? = "fallback")

@Serializable
data class WeirdKey(@SerialName("display name") val displayName: String)

@Serializable
@Mu.Tag("custom-app")
data class CustomApp(
    @Mu.Name("display-name") val displayName: String,
    val age: Int,
)

@Serializable
@Mu.Tag("variadic-cfg")
data class VariadicCfg(
    val name: String,
    @Mu.ZeroOrMore val tags: List<String> = emptyList(),
    @Mu.OneOrMore val features: List<String>,
    @Mu.Optional val nickname: String? = null,
)

@Serializable
enum class Color {
    RED,
    GREEN,
    BLUE,
}

@Serializable object Token

@Serializable data class WrappedToken(val token: Token)

@JvmInline @Serializable value class UserId(val value: Int)

@Serializable data class User(val id: UserId)

@Serializable
sealed class Animal {
    @Serializable @SerialName("cat") data class Cat(val lives: Int) : Animal()

    @Serializable @SerialName("dog") data object Dog : Animal()
}

@Serializable
sealed class ReservedPayload {
    @Serializable @SerialName("disc") data class WithDiscriminator(@SerialName("\$type") val type: String) : ReservedPayload()

    @Serializable @SerialName("value") data class WithValueField(@SerialName("\$value") val value: Int) : ReservedPayload()
}

@Serializable
sealed class Command {
    @Serializable @SerialName("run") data class Run(val task: String) : Command()

    @Serializable @SerialName("include") data class Include(val path: String) : Command()
}

@Serializable abstract class Shape

@Serializable @SerialName("circle") data class Circle(val r: Int) : Shape()

@Serializable @SerialName("rect") data class Rect(val w: Int, val h: Int) : Shape()

@Serializable data class ShapeBox(val shape: Shape)

@Serializable data class WithUUID(@Contextual val id: UUID)

class MuTreeFormatSpec {
    @Test
    fun `primitive roots round trip`() {
        assertTreeEquals("42", MuTrees.encodeToTree(42))
        assertEquals(42, MuTrees.decodeFromTree<Int>(parseOne("42")))

        assertTreeEquals("\"hello\"", MuTrees.encodeToTree("hello"))
        assertEquals("hello", MuTrees.decodeFromTree<String>(parseOne("\"hello\"")))
        assertEquals("name", MuTrees.decodeFromTree<String>(parseOne("name")))
    }

    @Test
    fun `class values encode as Mu maps and decode from parsed source`() {
        val value = Person("alice", 42, true)

        val tree = MuTrees.encodeToTree(value)
        assertTreeEquals("""(person :name "alice" :age 42 :active true)""", tree)

        val decoded =
            MuTrees.decodeFromTree<Person>(parseOne("""(person :name alice :age 42 :active true)"""))
        assertEquals(value, decoded)
    }

    @Test
    fun `lists maps and friendly string map keys round trip`() {
        val value = Inventory(ids = listOf(1, 2, 3), counts = linkedMapOf("alpha" to 1, "display name" to 2))

        val tree = MuTrees.encodeToTree(value)
        assertTreeEquals("""(inventory :ids [1 2 3] :counts {alpha: 1, "display name": 2})""", tree)
        assertEquals(value, MuTrees.decodeFromTree<Inventory>(tree))
    }

    @Test
    fun `nulls and defaults decode correctly`() {
        val decoded = MuTrees.decodeFromTree<MaybeValue>(parseOne("""(maybe-value :present null)"""))
        assertEquals(MaybeValue(present = null, fallback = "fallback"), decoded)
    }

    @Test
    fun `single field classes use group syntax even when serial names are not atom safe`() {
        val tree = MuTrees.encodeToTree(WeirdKey("visible"))
        assertTreeEquals("""(weird-key "visible")""", tree)
        assertEquals(WeirdKey("visible"), MuTrees.decodeFromTree<WeirdKey>(tree))
    }

    @Test
    fun `mu tag and mu name annotations override serializer defaults`() {
        val value = CustomApp(displayName = "alice", age = 42)
        val tree = MuTrees.encodeToTree(value)

        assertTreeEquals("""(custom-app :display-name "alice" :age 42)""", tree)
        assertEquals(value, MuTrees.decodeFromTree<CustomApp>(tree))
    }

    @Test
    fun `mu arity annotations use repeated group arguments`() {
        val source = """(variadic-cfg "demo" :tags "a" "b" :features "f1" "f2")"""
        val decoded = MuTrees.decodeFromTree<VariadicCfg>(parseOne(source))

        assertEquals(
            VariadicCfg(
                name = "demo",
                tags = listOf("a", "b"),
                features = listOf("f1", "f2"),
                nickname = null,
            ),
            decoded,
        )
        assertTreeEquals(
            """(variadic-cfg :name "demo" :tags "a" "b" :features "f1" "f2")""",
            MuTrees.encodeToTree(decoded),
        )
    }

    @Test
    fun `enum values round trip as atoms`() {
        assertTreeEquals("GREEN", MuTrees.encodeToTree(Color.GREEN))
        assertEquals(Color.BLUE, MuTrees.decodeFromTree<Color>(parseOne("BLUE")))
    }

    @Test
    fun `objects round trip at root and nested`() {
        assertTreeEquals("(token)", MuTrees.encodeToTree(Token))
        assertEquals(Token, MuTrees.decodeFromTree<Token>(parseOne("(token)")))

        val wrapped = WrappedToken(Token)
        assertTreeEquals("(wrapped-token (token))", MuTrees.encodeToTree(wrapped))
        assertEquals(wrapped, MuTrees.decodeFromTree<WrappedToken>(parseOne("(wrapped-token (token))")))
    }

    @Test
    fun `objects reject extra fields by default`() {
        val ex =
            assertFailsWith<SerializationException> {
                MuTrees.decodeFromTree<Token>(parseOne("{extra: 1}"))
            }

        assertEquals(true, ex.message!!.contains("Unknown Mu field 'extra'"))
    }

    @Test
    fun `objects can ignore extra fields when configured`() {
        val format = MuTreeFormat(ignoreUnknownKeys = true)
        assertEquals(Token, format.decodeFromTree<Token>(parseOne("{extra: 1}")))
    }

    @Test
    fun `inline value classes round trip`() {
        val value = User(UserId(7))
        val tree = MuTrees.encodeToTree(value)
        assertTreeEquals("(user 7)", tree)
        assertEquals(value, MuTrees.decodeFromTree<User>(tree))
    }

    @Test
    fun `sealed polymorphism uses group tags by default`() {
        val cat: Animal = Animal.Cat(9)
        val dog: Animal = Animal.Dog

        assertTreeEquals("""(cat 9)""", MuTrees.encodeToTree<Animal>(cat))
        assertTreeEquals("""(dog)""", MuTrees.encodeToTree<Animal>(dog))

        assertEquals(cat, MuTrees.decodeFromTree<Animal>(parseOne("""(cat :lives 9)""")))
        assertEquals(dog, MuTrees.decodeFromTree<Animal>(parseOne("""(dog)""")))
    }

    @Test
    fun `custom discriminator still supports legacy map polymorphism`() {
        val format = MuTreeFormat(classDiscriminator = "_kind")
        assertTreeEquals("""(cat 4)""", format.encodeToTree<Animal>(Animal.Cat(4)))
        assertEquals(Animal.Cat(4), format.decodeFromTree<Animal>(parseOne("""{_kind: "cat", lives: 4}""")))
    }

    @Test
    fun `open polymorphism round trips with serializers module`() {
        val module = SerializersModule {
            polymorphic(Shape::class) {
                subclass(Circle::class)
                subclass(Rect::class)
            }
        }
        val format = MuTreeFormat(serializersModule = module)

        val rootSerializer = PolymorphicSerializer(Shape::class)
        val rootValue: Shape = Circle(7)
        val rootTree = format.encodeToTree(rootSerializer, rootValue)
        assertTreeEquals("""(circle 7)""", rootTree)
        assertEquals(rootValue, format.decodeFromTree(rootSerializer, rootTree))

        val nested = ShapeBox(Rect(3, 5))
        val nestedTree = format.encodeToTree(ShapeBox.serializer(), nested)
        assertTreeEquals("""(shape-box (rect :w 3 :h 5))""", nestedTree)
        assertEquals(nested, format.decodeFromTree(ShapeBox.serializer(), nestedTree))
    }

    @Test
    fun `contextual serializers round trip`() {
        val module = SerializersModule { contextual(UUID::class, UUIDAsString) }
        val format = MuTreeFormat(serializersModule = module)
        val value = WithUUID(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))

        val tree = format.encodeToTree(WithUUID.serializer(), value)
        assertTreeEquals("""(with-u-u-i-d "123e4567-e89b-12d3-a456-426614174000")""", tree)
        assertEquals(value, format.decodeFromTree(WithUUID.serializer(), tree))
    }

    @Test
    fun `unknown class fields fail by default`() {
        val ex =
            assertFailsWith<SerializationException> {
                MuTrees.decodeFromTree<Person>(
                    parseOne("""{name: alice, age: 42, active: true, extra: 99}""")
                )
            }

        assertEquals(true, ex.message!!.contains("Unknown Mu field 'extra'"))
    }

    @Test
    fun `ignoreUnknownKeys accepts extra fields`() {
        val format = MuTreeFormat(ignoreUnknownKeys = true)
        val value =
            format.decodeFromTree<Person>(
                parseOne("""{name: alice, age: 42, active: true, extra: 99}""")
            )

        assertEquals(Person("alice", 42, true), value)
    }

    @Test
    fun `missing polymorphic discriminator fails`() {
        val ex =
            assertFailsWith<SerializationException> {
                MuTrees.decodeFromTree<Animal>(parseOne("""{lives: 9}"""))
            }

        assertEquals(true, ex.message!!.contains("Missing polymorphic discriminator"))
    }

    @Test
    fun `reserved polymorphic value field wrapper is supported`() {
        val module = SerializersModule {
            polymorphic(Shape::class) {
                subclass(Circle::class)
                subclass(Rect::class)
            }
        }
        val format = MuTreeFormat(serializersModule = module)
        val serializer = PolymorphicSerializer(Shape::class)
        val tree = parseOne("""{${'$'}type: "circle", ${'$'}value: {r: 11}}""")

        assertEquals(Circle(11), format.decodeFromTree(serializer, tree))
    }

    @Test
    fun `decode many from string supports sealed top level streams`() {
        val source =
            """
            (run "lint")
            (include "defaults.mu")
            (run "test")
            """.trimIndent()

        val commands = MuTrees.decodeManyFromString<Command>(source)

        assertEquals(
            listOf(
                Command.Run("lint"),
                Command.Include("defaults.mu"),
                Command.Run("test"),
            ),
            commands,
        )
    }

    @Test
    fun `decode from file and path support sealed top level streams`() {
        val source =
            """
            (run "lint")
            (include "defaults.mu")
            """.trimIndent()
        val path = createTempFile(prefix = "mu-tree-format-", suffix = ".mu")

        try {
            path.writeText(source)

            assertEquals(
                listOf(
                    Command.Run("lint"),
                    Command.Include("defaults.mu"),
                ),
                MuTrees.decodeManyFromFile<Command>(path.toFile()),
            )
            assertEquals(
                listOf(
                    Command.Run("lint"),
                    Command.Include("defaults.mu"),
                ),
                MuTrees.decodeManyFromFile<Command>(path),
            )
            assertEquals(
                Command.Run("lint"),
                MuTrees.decodeFromFile<Command>(
                    path.toFile().apply {
                        writeText("""(run "lint")""")
                    }
                ),
            )
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `single source decode requires exactly one top level expression`() {
        val ex =
            assertFailsWith<SerializationException> {
                MuTrees.decodeFromString<Command>(
                    """
                    (run "lint")
                    (run "test")
                    """.trimIndent()
                )
            }

        assertEquals(true, ex.message!!.contains("exactly one top-level expression"))
    }

    @Test
    fun `decode many reports the failing top level index`() {
        val ex =
            assertFailsWith<SerializationException> {
                MuTrees.decodeManyFromString<Command>(
                    """
                    (run "lint")
                    (unknown "missing-tag")
                    """.trimIndent()
                )
            }

        assertEquals(true, ex.message!!.contains("\$[1]"))
    }
}
