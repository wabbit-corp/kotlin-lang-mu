// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.mu.printer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import one.wabbit.mu.parser.MuParsedExpr
import one.wabbit.mu.parser.MuParser
import one.wabbit.mu.runtime.Mu

private val ID_NAME = setOf("id", "name")

private fun parseOne(source: String): MuParsedExpr = MuParser.parse(source).single()

@Serializable
sealed interface TopForm {
    @Serializable
    data class AppJvm(
        val name: String,
        val main: String,
        val ports: List<Int>,
        val env: Map<String, String>,
    ) : TopForm

    @Serializable
    data class Include(val path: String) : TopForm
}

@Serializable
data class Demo(val name: String, val main: String)

@Serializable
data class Service(
    val name: String,
    @param:Mu.Optional val nickname: String? = null,
    @param:Mu.OneOrMore val features: List<String>,
)

@Serializable
data class Aliased(
    @param:Mu.Name("display-name") val firstName: String,
    val lastName: String,
)

@Serializable
data class Slugged(val slug: String, val main: String)

class MuSourceSpec {
    @Test
    fun `encode many to pretty string renders document like output`() {
        val source: List<TopForm> =
            listOf(
                TopForm.AppJvm(
                    name = "billing-api",
                    main = "billing.Main",
                    ports = listOf(8080, 8443),
                    env = linkedMapOf("profile" to "prod", "region" to "us-east-1"),
                ),
                TopForm.Include(path = "shared/logging.mu"),
            )

        val result = MuSource.encodeManyToPrettyString(source, firstPositionalFields = ID_NAME)

        assertEquals(
            """
            (app-jvm "billing-api"
              :main "billing.Main"
              :ports [8080 8443]
              :env {
                profile: prod,
                region: us-east-1
              }
            )

            (include "shared/logging.mu")
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `encode many to compact string renders document like output`() {
        val source: List<TopForm> =
            listOf(
                TopForm.AppJvm(
                    name = "billing-api",
                    main = "billing.Main",
                    ports = listOf(8080, 8443),
                    env = linkedMapOf("profile" to "prod", "region" to "us-east-1"),
                ),
                TopForm.Include(path = "shared/logging.mu"),
            )

        val result = MuSource.encodeManyToCompactString(source, firstPositionalFields = ID_NAME, maxLineLength = 140)

        assertEquals(
            """(app-jvm "billing-api" :main "billing.Main" :ports [8080 8443] :env {profile: prod, region: us-east-1})
(include "shared/logging.mu")""",
            result,
        )
    }

    @Test
    fun `encode to string supports indent none vs pretty`() {
        val value = Demo(name = "billing-api", main = "billing.Main")

        val concise =
            MuSource.encodeToString(
                value,
                indent = null,
                firstPositionalFields = ID_NAME,
                singleFieldPositional = false,
            )
        val pretty =
            MuSource.encodeToString(
                value,
                indent = 2,
                firstPositionalFields = ID_NAME,
                singleFieldPositional = false,
            )

        assertEquals("""(demo "billing-api" :main "billing.Main")""", concise)
        assertEquals("""(demo "billing-api" :main "billing.Main")""", pretty)
    }

    @Test
    fun `field names default and first positional flag`() {
        val value = Demo(name = "billing-api", main = "billing.Main")

        val named = MuSource.encodeToCompactString(value, singleFieldPositional = false)
        val positional =
            MuSource.encodeToCompactString(
                value,
                firstPositionalFields = ID_NAME,
                singleFieldPositional = false,
            )

        assertEquals("""(demo :name "billing-api" :main "billing.Main")""", named)
        assertEquals("""(demo "billing-api" :main "billing.Main")""", positional)
    }

    @Test
    fun `custom first positional fields`() {
        val value = Slugged(slug = "billing-api", main = "billing.Main")

        val default =
            MuSource.encodeToCompactString(
                value,
                firstPositionalFields = ID_NAME,
                singleFieldPositional = false,
            )
        val custom =
            MuSource.encodeToCompactString(
                value,
                firstPositionalFields = setOf("slug"),
                singleFieldPositional = false,
            )

        assertEquals("""(slugged :slug "billing-api" :main "billing.Main")""", default)
        assertEquals("""(slugged "billing-api" :main "billing.Main")""", custom)
    }

    @Test
    fun `annotated values optional and vararg`() {
        val value = Service(name = "api", nickname = null, features = listOf("http", "metrics"))

        val result =
            MuSource.encodeToCompactString(
                value,
                firstPositionalFields = ID_NAME,
                singleFieldPositional = false,
            )

        assertEquals("""(service "api" :features "http" "metrics")""", result)
    }

    @Test
    fun `annotated field name override`() {
        val value = Aliased(firstName = "Ada", lastName = "Lovelace")

        val result = MuSource.encodeToCompactString(value, singleFieldPositional = false)

        assertEquals("""(aliased :display-name "Ada" :last-name "Lovelace")""", result)
    }

    @Test
    fun `max line length wraps concise output`() {
        val value =
            TopForm.AppJvm(
                name = "billing-api",
                main = "billing.Main",
                ports = listOf(8080, 8443),
                env = linkedMapOf("profile" to "prod", "region" to "us-east-1"),
            )

        val wrapped =
            MuSource.encodeToCompactString(
                value,
                maxLineLength = 40,
                firstPositionalFields = ID_NAME,
            )

        assertEquals(true, "\n" in wrapped)
        assertEquals(true, wrapped.startsWith("""(app-jvm "billing-api""""))
        assertEquals(true, "\n  :main " in wrapped)
    }

    @Test
    fun `render pretty document accepts parsed ast`() {
        val doc = MuParser.parse("""(app-jvm "demo" :main "demo.Main") (include "shared/logging.mu")""")

        val result = MuSource.renderPrettyDocument(doc, maxLineLength = 40)

        assertEquals(
            """(app-jvm "demo" :main "demo.Main")

(include "shared/logging.mu")""",
            result,
        )
    }

    @Test
    fun `preserve spans keeps raw numeric tokens when available`() {
        val expr = parseOne("""(demo 1. 1_000)""")

        assertEquals("""(demo 1. 1_000)""", MuSource.render(expr, preserveSpans = true))
        assertEquals("""(demo 1.0 1000)""", MuSource.render(expr, preserveSpans = false))
    }

    @Test
    fun `one or more empty raises`() {
        val value = Service(name = "api", nickname = null, features = emptyList())

        assertFailsWith<SerializationException> {
            MuSource.encodeToCompactString(value)
        }
    }
}
