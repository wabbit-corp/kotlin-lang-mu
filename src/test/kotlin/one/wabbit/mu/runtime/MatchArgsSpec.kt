package one.wabbit.mu.runtime

import one.wabbit.data.Either
import one.wabbit.data.Left
import one.wabbit.data.Right
import one.wabbit.mu.MuException
import kotlin.test.*

class ArgMatchingSpec {
    private fun parseParamDefs(args: String): List<Pair<ArgArity, String>> {
        val args = args.split(Regex("\\s+"))
        val result = mutableListOf<Pair<ArgArity, String>>()
        for (arg in args) {
            if (arg.isEmpty()) continue
            if (arg.endsWith("?")) result.add(Pair(ArgArity.Optional, arg.dropLast(1)))
            else if (arg.endsWith("*")) result.add(Pair(ArgArity.ZeroOrMore, arg.dropLast(1)))
            else if (arg.endsWith("+")) result.add(Pair(ArgArity.OneOrMore, arg.dropLast(1)))
            else result.add(Pair(ArgArity.Required, arg))
        }
        return result
    }

    private fun parseArgs(args: String): List<Either<String, String>> {
        val args = args.split(Regex("\\s+"))
        val result = mutableListOf<Either<String, String>>()
        for (arg in args) {
            if (arg.isEmpty()) continue
            if (arg.startsWith(":")) result.add(Left(arg.drop(1)))
            else result.add(Right(arg))
        }
        return result
    }

    @Test fun `test helper functions`() {
        val paramDefs = parseParamDefs("x y z?")
        assertEquals(listOf(Pair(ArgArity.Required, "x"), Pair(ArgArity.Required, "y"), Pair(ArgArity.Optional, "z")), paramDefs)

        val args = parseArgs("x y :z z")
        assertEquals(listOf(Right("x"), Right("y"), Left("z"), Right("z")), args)

        assertEquals(emptyList(), parseParamDefs(""))
        assertEquals(emptyList(), parseArgs(""))
    }

    // Helper to assert successful matching
    private fun assertMatch(
        paramDefs: String,
        args: String,
        expectedMap: Map<String, List<String>>,
        message: String? = null
    ) {
        val params = parseParamDefs(paramDefs)
        val arguments = parseArgs(args)
        val actualMap = try {
            matchArgs(params, arguments)
        } catch (e: MuException) {
            fail("Expected successful match (params={$paramDefs}, args={$args}), but failed with ${e::class.simpleName}: ${e.message}${message?.let { "\n$it" } ?: ""}", e)
        }
        assertEquals(expectedMap, actualMap, message)
    }

    // Helper to assert matching fails with a specific exception
    private inline fun <reified T : MuException> assertMatchFails(
        paramDefs: String,
        args: String,
        message: String? = null,
        crossinline condition: (T) -> Boolean = { true } // Optional check on exception properties
    ) {
        val params = parseParamDefs(paramDefs)
        val arguments = parseArgs(args)
        val exception = assertFailsWith<T>(message) {
            matchArgs(params, arguments)
        }
        assertTrue(condition(exception), "Exception condition not met: $exception (params={$paramDefs}, args={$args})")
    }


    @Test fun `empty definitions or args`() {
        assertMatch("", "", emptyMap(), "Empty params, empty args")
        assertMatchFails<TooManyArgumentsException>("", "a", "Empty params, one arg") { it.values == listOf("a") }
        assertMatchFails<UnknownArgumentException>("", ":a a", "Empty params, named arg") { it.names == listOf("a") }

        assertMatch("x?", "", mapOf("x" to emptyList()), "Optional param, empty args")
        assertMatch("x*", "", mapOf("x" to emptyList()), "Vararg param, empty args")
        assertMatchFails<MissingArgumentException>("x", "", "Required param, empty args") { it.names == listOf("x") }
        assertMatchFails<MissingArgumentException>("x+", "", "OneOrMore param, empty args") { it.names == listOf("x") }
        assertMatchFails<MissingArgumentException>("x y", "", "Required params, empty args") { it.names == listOf("x", "y") }
    }

    @Test fun `duplicate variations`() {
        assertMatchFails<DuplicateArgumentException>("x y", "a :x b", "Duplicate: Positional then named") { it.name == "x" }
        assertMatchFails<TooManyArgumentsException>("x y", ":x a b c") { it.values == listOf("c") }
        assertMatch("x y*", "a :y b :y c", mapOf("x" to listOf("a"), "y" to listOf("b", "c")), "Duplicate vararg value assignment")
    }

    @Test fun `req req req req`() {
        val params = "x y z w"
        val expected = mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z"), "w" to listOf("w"))

        assertMatch(params, "x y z w", expected, "All positional")
        assertMatch(params, ":x x :y y :z z :w w", expected, "All named, in order")
        assertMatch(params, ":y y :x x :z z :w w", expected, "All named, out of order")
        assertMatch(params, ":x x y z w", expected, "First named, rest positional")
        assertMatch(params,":x x y :w w :z z", expected, "First named, out of order named at the end")

        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params,":y y x z w", "Out of order named args")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params,"x :z z y w", "Out of order named args")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params,"x y :w w z", "Out of order named args")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params,":x x y :w w z", "Out of order named args")
        assertMatchFails<TooManyArgumentsException>(params, "x y z w extra", "Too many args") { it.values == listOf("extra") }
        assertMatchFails<MissingArgumentException>(params, "x y z", "Missing arg w") { it.names == listOf("w") }
        assertMatchFails<MissingArgumentException>(params, ":x x :y y z", "Missing arg w") { it.names == listOf("w") }
        assertMatchFails<DuplicateArgumentException>(params, "x y z w :x extra", "Duplicate named arg x") { it.name == "x" }
        assertMatchFails<UnknownArgumentException>(params, "x y z :unknown u", "Unknown named arg") { it.names == listOf("unknown") }
        assertMatchFails<NamedArgumentRequiresValueException>(params, "x y z :w", "Named arg w requires value") { it.names == listOf("w") }
    }

    @Test fun `req req star`() {
        val params = "x y z*"
        assertMatch(params, "x y", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to emptyList()), "No varargs")
        assertMatch(params, "x y z", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")), "One vararg")
        assertMatch(params, "x y z1 z2 z3", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1", "z2", "z3")), "Multiple varargs")

        assertMatchFails<MissingArgumentException>(params, "x", "Missing y") { it.names == listOf("y") }
    }

    // (define (f x y z+) ...)
    // (f x y)                 - BAD
    // (f x y z)               - x=x, y=y, z=[z]
    // (f x y z1 z2 z3)        - x=x, y=y, z=[z1, z2, z3]
    @Test fun `req req plus`() {
        val params = "x y z+"

        assertMatch(params, "x y z1", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1")), "Vararg required single")
        assertMatch(params, "x y z1 z2 z3", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1", "z2", "z3")), "Vararg required multiple")
        assertMatch(params, ":y y :x x :z z1 z2", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1", "z2")), "All named, required vararg")

        assertMatchFails<MissingArgumentException>(params, "x y", "Vararg required missing") { it.names == listOf("z") }
        assertMatchFails<MissingArgumentException>(params, ":x x :y y", "Vararg required missing (named)") { it.names == listOf("z") }
        assertMatchFails<NamedArgumentRequiresValueException>(params, ":x x :y y :z", "Named required vararg needs value") { it.names == listOf("z") }
    }

    // (define (f x y z?) ...)
    // (f x y)                 - OK, z=null
    // (f x y z)               - OK, z=z
    // (f x y z1 z2 z3)        - BAD, too many arguments
    @Test fun `req req opt`() {
        val params = "x y z?"

        assertMatch(params, "x y", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to emptyList()), "Optional absent")
        assertMatch(params, "x y z1", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1")), "Optional present")
        assertMatch(params, ":x x :y y", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to emptyList()), "Optional absent (named)")
        assertMatch(params, ":y y :x x :z z1", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1")), "Optional present (named)")
        assertMatch(params, ":z z1 :y y :x x", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1")), "Optional present (out of order)")

        // Failures
        assertMatchFails<TooManyArgumentsException>(params, "x y z1 z2", "Too many args for optional") // { it.values == listOf("z2") }
        assertMatchFails<DuplicateArgumentException>(params, "x y :z z1 :z z2", "Duplicate optional named") { it.name == "z" }
    }

    // (define (f x y* z) ...)
    // (f x z)                 - x=x, y=[], z=z
    // (f x y z)               - x=x, y=[y], z=z
    // (f x y1 y2 y3 z)        - x=x, y=[y1, y2, y3], z=z
    @Test fun `req star req`() {
        val params = "x y* z"

        assertMatch(params, "x z", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z")), "Positional, vararg empty")
        assertMatch(params, "x y1 z", mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z")), "Positional, vararg single")
        assertMatch(params, "x y1 y2 y3 z", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2", "y3"), "z" to listOf("z")), "Positional, vararg multiple")
        assertMatch(params, ":x x :z z", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z")), "Named, vararg empty")
        assertMatch(params, "x :z z", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z")), "Positional x, named z")
        assertMatch(params, "x :y y1 :z z", mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z")), "Positional x, named y, named z")
        assertMatch(params, ":x x :y y :z z", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")), "All named, in order")
        assertMatch(params, ":y y :x x :z z", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")), "All named, out of order")
        assertMatch(params, ":x x :y y1 y2 y3 :z z", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2", "y3"), "z" to listOf("z")), "First named, rest positional")
        assertMatch(params, ":x x :y y1 :y y2 :z z", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2"), "z" to listOf("z")), "Named, vararg multiple")
        assertMatch(params, ":x x :z z :y y1 y2 y3", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2", "y3"), "z" to listOf("z")), "First named, out of order named at the end")
        assertMatch(params, "x y1 :z z", mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z")), "Positional x, positional y*, named z")

        assertMatchFails<MissingArgumentException>(params, ":y a", "ReqStarReq: Named star, missing reqs") { it.names == listOf("x", "z") }
        assertMatchFails<MissingArgumentException>(params, ":x a :y b", "ReqStarReq: Named x, star, missing z") { it.names == listOf("z") }
        assertMatchFails<MissingArgumentException>(params, ":y b :z c", "ReqStarReq: Named star, z, missing x") { it.names == listOf("x") }
    }

    // (define (f x y* z*) ...)
    // (f x)                                     - x=x, y=[], z=[]
    // (f x z1)                                  - BAD, too prone to error
    // (f x z1 z2)                               - BAD, too prone to error
    // (f x z1 z2 z3)                            - BAD, too prone to error
    // (f x :y y)                                - x=x, y=[y], z=[]
    // (f x :z z)                                - x=x, y=[], z=[z]
    // (f x :y y1 :y y2 :y y3 z1 z2 z3)          - BAD, too prone to error
    // (f x :y y1 y2 y3 :z z1 z2 z3)             - x=x, y=[y1, y2, y3], z=[z1, z2, z3]
    // (f x :y y1 :y y2 :y y3 :z z1 z2 z3)       - x=x, y=[y1, y2, y3], z=[z1, z2, z3]
    // (f x :y y1 :y y2 :y y3 :z z1 :z z2 :z z3) - x=x, y=[y1, y2, y3], z=[z1, z2, z3]
    @Test fun `req star star`() {
        // Highly ambiguous positionally. Requires named arguments.
        val params = "x y* z*"

        assertMatch(params, "x", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to emptyList()), "Only required x")
        // Positional calls are ambiguous and should fail or have defined (likely undesirable) greedy behavior.
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "x a", "Ambiguous positional a") // Could go to y* or z*
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "x a b", "Ambiguous positional a, b")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "x a b c", "Ambiguous positional a, b, c")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "x y1 :z z1", "Positional x, positional y*, named z")

        // Using named arguments:
        assertMatch(params, ":x x", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to emptyList()), "Named x only")
        assertMatch(params, "x :y y1", mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to emptyList()), "Named y")
        assertMatch(params, "x :z z1", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z1")), "Named z")
        assertMatch(params, "x :y y1 :z z1", mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z1")), "Named y and z")
        assertMatch(params, "x :y y1 y2 :z z1 z2 z3", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2"), "z" to listOf("z1", "z2", "z3")), "Named multiple y and z")
        assertMatch(params, "x :y y1 :y y2 :y y3 :z z1 z2 z3", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2", "y3"), "z" to listOf("z1", "z2", "z3")), "Named multiple times y and z")
        assertMatch(params, "x :y y1 :y y2 :z z1 :z z2 :z z3", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2"), "z" to listOf("z1", "z2", "z3")), "Named multiple times y and z")
        assertMatch(params, "x :y y1 y2", mapOf("x" to listOf("x"), "y" to listOf("y1", "y2"), "z" to listOf()), "Positional x, named y")
    }

    @Test fun `req star star req req`() {
        // Purely positional is impossible to parse correctly.
        assertMatchFails<AmbiguousPositionalArgumentException>("x y* z* w a", "x y1 z1 w a", "Ambiguous positional")

        // Must use named args for clarity
        assertMatch(
            "x y* z* w a", "x :y y1 :z z1 :w w :a a",
            mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z1"), "w" to listOf("w"), "a" to listOf("a")),
            "All named"
        )
        assertMatch(
            "x y* z* w a", "x :w w :a a",
            mapOf("x" to listOf("x"), "y" to emptyList(), "z" to emptyList(), "w" to listOf("w"), "a" to listOf("a")),
            "Named required only"
        )
        assertMatch(
            "x y* z* w a", "x :y y1 y2 :w w :a a",
            mapOf(
                "x" to listOf("x"),
                "y" to listOf("y1", "y2"),
                "z" to emptyList(),
                "w" to listOf("w"),
                "a" to listOf("a")
            ),
            "Named y*, named required"
        )
        assertMatch(
            "x y* z* w a", "x :z z1 z2 :w w :a a",
            mapOf(
                "x" to listOf("x"),
                "y" to emptyList(),
                "z" to listOf("z1", "z2"),
                "w" to listOf("w"),
                "a" to listOf("a")
            ),
            "Named z*, named required"
        )

        // Mixing positional x with named others
        assertMatch(
            "x y* z* w a", "x :y y1 :z z1 :w w :a a",
            mapOf("x" to listOf("x"), "y" to listOf("y1"), "z" to listOf("z1"), "w" to listOf("w"), "a" to listOf("a")),
            "Positional x, rest named"
        )

        // What about positional values after named varargs?
        assertMatchFails<MissingArgumentException>(
            "x y* z* w a", "a :y b :z c d e",
            "Named varargs, positional required at end"
        )

        // `x :y y1 w a` -> w, a fill required. z* is empty.
        assertMatchFails<MissingArgumentException>(
            "x y* z* w a", "a :y b c d",
            "Named y*, empty z*, positional required at end"
        )
    }

    @Test fun `regression`() {
        // Signature from a real-world scenario causing issues
        val params = "from preceded-by? not-preceded-by? to probability min-alcohol?"
        // Arities: Req, Opt, Opt, Req, Req, Opt

        // The problematic call structure
        val args = ":from x :to y :probability 6 :min-alcohol 80"

        val expected = mapOf(
            "from" to listOf("x"),
            "preceded-by" to emptyList(),       // Optional, not provided
            "not-preceded-by" to emptyList(), // Optional, not provided
            "to" to listOf("y"),
            "probability" to listOf("6"),
            "min-alcohol" to listOf("80")       // Optional, provided
        )

        assertMatch(params, args, expected, "Regression case with optional args provided by name")
    }

    @Test fun `req opt req`() {
        val params = "x y? z" // Req, Opt, Req
        assertMatch(params, "x z", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z")), "Optional middle absent")
        assertMatch(params, "x y z", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")), "Optional middle present")
        assertMatch(params, "x :z z", mapOf("x" to listOf("x"), "y" to emptyList(), "z" to listOf("z")), "Optional middle absent, named z")
        assertMatch(params, "x :y y :z z", mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")), "Optional middle present, named y and z")
        // Should positional 'y' fill the optional 'y' correctly? Yes.

        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params, ":z z x", "Optional middle absent, out of order named z")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params, ":z z x y", "Positional y after out-of-order z")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params, ":y y :z z x", "Optional middle present, out of order named y, z")
        assertMatchFails<TooManyArgumentsException>(params, "x :z z y", "Optional middle filled positionally after named")
        assertMatchFails<TooManyArgumentsException>(params, "x y z extra", "Too many args")
        assertMatchFails<MissingArgumentException>(params, "x", "Missing z") { it.names == listOf("z") }
        assertMatchFails<MissingArgumentException>(params, ":y y", "Missing x and z") { it.names == listOf("x", "z") }
    }

    @Test fun `opt star`() {
        val params = "x? y*" // Opt, ZeroOrMore
        assertMatch(params, "", mapOf("x" to emptyList(), "y" to emptyList()), "Opt+Var: None")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a", "Opt+Var: one arg")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b", "Opt+Var: two args")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "Opt+Var: three args")
        assertMatch(params, ":x vx", mapOf("x" to listOf("vx"), "y" to emptyList()), "Opt+Var: Named Opt only")
        assertMatch(params, ":y vy1", mapOf("x" to emptyList(), "y" to listOf("vy1")), "Opt+Var: Named Var only")
        assertMatch(params, ":y y1 y2", mapOf("x" to emptyList(), "y" to listOf("y1", "y2")), "Opt+Var: Named Var only")
        assertMatch(params, ":x vx :y vy1 vy2", mapOf("x" to listOf("vx"), "y" to listOf("vy1", "vy2")), "Opt+Var: Named Opt and Var")
        assertMatch(params, ":y vy1 vy2 :x vx", mapOf("x" to listOf("vx"), "y" to listOf("vy1", "vy2")), "Opt+Var: Named Var and Opt out of order")
    }

    @Test fun `plus req`() {
        val params = "x+ y" // OneOrMore, Req
         assertMatch(params, "a b", mapOf("x" to listOf("a"), "y" to listOf("b")), "Plus+Req: Single plus")
         assertMatch(params, "a b c", mapOf("x" to listOf("a", "b"), "y" to listOf("c")), "Plus+Req: Multi plus")
         assertMatch(params, ":y a :x b c", mapOf("x" to listOf("b", "c"), "y" to listOf("a")), "Plus+Req: Named out of order")
         assertMatch(params, ":x a b :y c", mapOf("x" to listOf("a", "b"), "y" to listOf("c")), "Plus+Req: Named in order")

        // Failures for x+ y
        assertMatchFails<MuException>(params, "a", "Plus+Req: Missing y") // Either one of x or y, any exception will do
        assertMatchFails<MissingArgumentException>(params, ":y a", "Plus+Req: Missing x+ (named y)") { it.names == listOf("x") }
        assertMatchFails<MissingArgumentException>(params, ":x a", "Plus+Req: Missing y (named x+)") { it.names == listOf("y") }
        assertMatchFails<NamedArgumentRequiresValueException>(params, ":y a :x", "Plus+Req: Named x+ needs value") { it.names == listOf("x") }
        assertMatchFails<NamedArgumentRequiresValueException>(params, ":x :y a", "Plus+Req: Named x+ needs value, got :y") { it.names == listOf("x") }
    }

    @Test fun `plus star`() {
        val params2 = "a+ b*" // OneOrMore, ZeroOrMore
        assertMatch(params2, "x", mapOf("a" to listOf("x"), "b" to emptyList()), "Plus+Star: one argument")
        assertMatchFails<AmbiguousPositionalArgumentException>(params2, "x y", "Plus+Star: two arguments")
        assertMatchFails<AmbiguousPositionalArgumentException>(params2, "x y z w", "Plus+Star: more arguments")
        assertMatch(params2, ":a x :b y", mapOf("a" to listOf("x"), "b" to listOf("y")), "Plus+Star: Named")
        assertMatch(params2, ":b x y :a z w", mapOf("a" to listOf("z", "w"), "b" to listOf("x", "y")), "Plus+Star: Named out of order")
        assertMatchFails<MissingArgumentException>(params2, "", "Plus+Star: Missing a+") { it.names == listOf("a") }
    }

    @Test fun `plus opt`() {
        val params3 = "a+ b?" // OneOrMore, Optional
        assertMatch(params3, "x", mapOf("a" to listOf("x"), "b" to emptyList()), "Plus+Opt: one argument")
        assertMatchFails<AmbiguousPositionalArgumentException>(params3, "x y", "Plus+Opt: two arguments")
        assertMatchFails<AmbiguousPositionalArgumentException>(params3, "x y z", "Plus+Opt: three arguments")
        assertMatch(params3, ":a x :b y", mapOf("a" to listOf("x"), "b" to listOf("y")), "Plus+Star: Named")
        assertMatch(params3, ":b x :a y z", mapOf("a" to listOf("y", "z"), "b" to listOf("x")), "Plus+Star: Named out of order")
        assertMatchFails<MissingArgumentException>(params3, "", "Plus+Opt: Missing a+") { it.names == listOf("a") }
    }

    @Test fun `req opt star req`() {
        val params = "x y? z* w" // Req, Opt, Var, Req
        assertMatch(params, "a b", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to emptyList(), "w" to listOf("b")), "Skip Opt+Var: Positional")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "Skip Var: Positional")
        // Named args interacting with skips
        assertMatch(params, ":w a :x b", mapOf("x" to listOf("b"), "y" to emptyList(), "z" to emptyList(), "w" to listOf("a")), "Skip Opt+Var: Named out of order")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params, ":w a b", "Skip Opt+Var: Named w, positional x")
        assertMatch(params, "a :w b", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to emptyList(), "w" to listOf("b")), "Skip Opt+Var: Positional x, named w")
        assertMatchFails<PositionalArgumentsAfterOutOfOrderNamedArgumentException>(params, ":w a b c", "Skip Opt: Named w, positional x, z")
    }

    @Test fun `plus plus`() {
        val params = "x+ y+"
        assertMatchFails<MissingArgumentException>(params, "", "Plus+Plus: Empty") { it.names == listOf("x", "y") }
        assertMatchFails<MissingArgumentException>(params, "a", "Plus+Plus: One arg (missing y+)")
        // Positional 'a b' is ambiguous because 'a' satisfies x+, leaving 'b' for either x+ or y+
        assertMatch(params, "a b", mapOf("x" to listOf("a"), "y" to listOf("b")), "Plus+Plus: Two args (NOT ambiguous)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "Plus+Plus: Three args (ambiguous)")
        assertMatch(params, ":x a :y b", mapOf("x" to listOf("a"), "y" to listOf("b")), "Plus+Plus: Named")
        assertMatch(params, ":x a b :y c d", mapOf("x" to listOf("a", "b"), "y" to listOf("c", "d")), "Plus+Plus: Named multiple")
        assertMatch(params, ":y c d :x a b", mapOf("x" to listOf("a", "b"), "y" to listOf("c", "d")), "Plus+Plus: Named out of order")
        assertMatchFails<MissingArgumentException>(params, ":x a", "Plus+Plus: Named x, missing y+") { it.names == listOf("y") }
        assertMatchFails<MissingArgumentException>(params, ":y b", "Plus+Plus: Named y, missing x+") { it.names == listOf("x") }
    }

    @Test fun `star plus`() {
        val params = "x* y+"
        assertMatchFails<MissingArgumentException>(params, "", "Star+Plus: Empty") { it.names == listOf("y") }
        // Positional 'a' must go to y+
        assertMatch(params, "a", mapOf("x" to emptyList(), "y" to listOf("a")), "Star+Plus: One arg")
        // Positional 'a b' is ambiguous: 'a' could be x*, 'b' for y+ OR 'a','b' could be y+
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b", "Star+Plus: Two args (ambiguous)")
        assertMatch(params, ":y a", mapOf("x" to emptyList(), "y" to listOf("a")), "Star+Plus: Named y")
        assertMatch(params, ":x a :y b", mapOf("x" to listOf("a"), "y" to listOf("b")), "Star+Plus: Named x and y")
        assertMatch(params, ":x a b :y c d", mapOf("x" to listOf("a", "b"), "y" to listOf("c", "d")), "Star+Plus: Named multiple")
        assertMatchFails<MissingArgumentException>(params, ":x a", "Star+Plus: Named x, missing y+") { it.names == listOf("y") }
    }

    @Test fun `plus star plus`() {
        val params = "x+ y* z+"
        assertMatchFails<MissingArgumentException>(params, "", "Plus+Star+Plus: Empty") // Fails on x+
        assertMatchFails<MissingArgumentException>(params, "a", "Plus+Star+Plus: One arg")
        // 'a b': a must go to x+, b must go to z+
        assertMatch(params, "a b", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to listOf("b")), "Plus+Star+Plus: Two args")
        // 'a b c': a->x+, c->z+, b is ambiguous (y* or z+)
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "Plus+Star+Plus: Three args (ambiguous b)")
        // 'a b c d': a->x+, d->z+, 'b c' could be (y*=[b,c], z+=[d]) or (y*=[b], z+=[c,d]) -> ambiguous
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c d", "Plus+Star+Plus: Four args (ambiguous)")
        assertMatch(params, ":x a :z b", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to listOf("b")), "Plus+Star+Plus: Named x, z")
        assertMatch(params, ":x a :y b :z c", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c")), "Plus+Star+Plus: Named x, y, z")
        assertMatch(params, ":x a d :y b e :z c f", mapOf("x" to listOf("a", "d"), "y" to listOf("b", "e"), "z" to listOf("c", "f")), "Plus+Star+Plus: Named multiple")
    }

    @Test fun `plus opt req`() {
        val params1 = "x+ y? z" // Plus, Opt, Req
        assertMatchFails<MissingArgumentException>(params1, "", "PlusOptReq: Empty") // Fails on x+ or z
        assertMatch(params1, "a b", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to listOf("b")), "PlusOptReq: Two args (skip opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params1, "a b c", "PlusOptReq: Three args (fill opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params1, "a b c d", "PlusOptReq: Four args")
        assertMatch(params1, ":x a :z c", mapOf("x" to listOf("a"), "y" to emptyList(), "z" to listOf("c")), "PlusOptReq: Named x, z")
        assertMatch(params1, ":x a :y b :z c", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c")), "PlusOptReq: Named x, y, z")
        assertMatchFails<MissingArgumentException>(params1, ":x a", "PlusOptReq: Named x, missing z") { it.names == listOf("z") }
        assertMatchFails<MissingArgumentException>(params1, ":z c", "PlusOptReq: Named z, missing x+") { it.names == listOf("x") }
        assertMatchFails<MissingArgumentException>(params1, ":y a", "PlusOptReq: Named y, missing x+ and z") { it.names == listOf("x", "z") }
    }

    @Test fun `opt plus req`() {
        val params2 = "x? y+ z" // Opt, Plus, Req
        assertMatchFails<MissingArgumentException>(params2, "", "OptPlusReq: Empty") // Fails y+ or z
        assertMatch(params2, "a b", mapOf("x" to emptyList(), "y" to listOf("a"), "z" to listOf("b")), "OptPlusReq: Two args (skip opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params2, "a b c", "OptPlusReq: Three args (fill opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params2, "a b c d", "OptPlusReq: Four args")
        assertMatch(params2, ":y b :z c", mapOf("x" to emptyList(), "y" to listOf("b"), "z" to listOf("c")), "OptPlusReq: Named y, z")
        assertMatch(params2, ":x a :y b :z c", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c")), "OptPlusReq: Named x, y, z")
        assertMatchFails<MissingArgumentException>(params2, ":y b", "OptPlusReq: Named y, missing z") { it.names == listOf("z") }
        assertMatchFails<MissingArgumentException>(params2, ":x a :z c", "OptPlusReq: Named x, z, missing y+") { it.names == listOf("y") }
    }

    @Test fun `req plus opt req`() {
        val params3 = "x y+ z? w" // Req, Plus, Opt, Req
        assertMatchFails<MissingArgumentException>(params3, "", "ReqPlusOptReq: Empty") // Fails x, y+, or w
        assertMatch(params3, "a b c", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to emptyList(), "w" to listOf("c")), "ReqPlusOptReq: Three args (skip opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params3, "a b c d", "ReqPlusOptReq: Four args (fill opt)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params3, "a b c d e", "ReqPlusOptReq: Five args")
        assertMatch(params3, ":x a :y b :w d", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to emptyList(), "w" to listOf("d")), "ReqPlusOptReq: Named skip opt")
        assertMatch(params3, ":x a :y b :z c :w d", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c"), "w" to listOf("d")), "ReqPlusOptReq: Named fill opt")
        assertMatchFails<MissingArgumentException>(params3, ":x a :y b", "ReqPlusOptReq: Named x, y, missing w") { it.names == listOf("w") }
        assertMatchFails<MissingArgumentException>(params3, ":x a :w d", "ReqPlusOptReq: Named x, w, missing y+") { it.names == listOf("y") }
    }

    @Test fun `opt req`() {
        // Optional followed by required
        assertMatch("x? y", "b", mapOf("x" to emptyList(), "y" to listOf("b")), "OptReq: Required only")
        assertMatch("x? y", "a b", mapOf("x" to listOf("a"), "y" to listOf("b")), "OptReq: Both")
        assertMatch("x? y", ":y b", mapOf("x" to emptyList(), "y" to listOf("b")), "OptReq: Named required only")
        assertMatch("x? y", ":x a :y b", mapOf("x" to listOf("a"), "y" to listOf("b")), "OptReq: Named both")
        assertMatchFails<MissingArgumentException>("x? y", ":x a", "OptReq: Named opt, missing req") { it.names == listOf("y") }
    }

    @Test fun `star opt`() {
        // Vararg followed by optional
        assertMatch("x* y?", "", mapOf("x" to emptyList(), "y" to emptyList()), "StarOpt: None")
        // Positional 'a' could be x* or y? -> ambiguous
        assertMatchFails<AmbiguousPositionalArgumentException>("x* y?", "a", "StarOpt: One arg (ambiguous)")
        assertMatchFails<AmbiguousPositionalArgumentException>("x* y?", "a b", "StarOpt: Two args (ambiguous)")
        assertMatch("x* y?", ":x a", mapOf("x" to listOf("a"), "y" to emptyList()), "StarOpt: Named x")
        assertMatch("x* y?", ":y b", mapOf("x" to emptyList(), "y" to listOf("b")), "StarOpt: Named y")
        assertMatch("x* y?", ":x a b :y c", mapOf("x" to listOf("a", "b"), "y" to listOf("c")), "StarOpt: Named both")
    }

    @Test fun `star req star`() {
        val params = "x* y z*"
        assertMatchFails<MissingArgumentException>(params, "", "StarReqStar: Empty") // Fails y+ or z
        assertMatch(params, "a", mapOf("x" to emptyList(), "y" to listOf("a"), "z" to emptyList()), "StarReqStar: One arg")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b", "StarReqStar: Two args (ambiguity)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "StarReqStar: Three args (ambiguity)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c d", "StarReqStar: Four args (ambiguity)")
        assertMatch(params, ":y b :z c", mapOf("x" to emptyList(), "y" to listOf("b"), "z" to listOf("c")), "StarReqStar: Named y, z")
        assertMatch(params, ":x a :y b :z c", mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c")), "StarReqStar: Named x, y, z")
        assertMatch(params, ":y b", mapOf("x" to emptyList(), "y" to listOf("b"), "z" to emptyList()), "StarReqStar: Named y only")
        assertMatchFails<MissingArgumentException>(params, ":x a :z c", "StarReqStar: Named x, z, missing y") { it.names == listOf("y") }
    }

    @Test fun `star req req star`() {
        val params = "x* y z w*"
        assertMatchFails<MissingArgumentException>(params, "", "StarReqReqStar: Empty") // Fails y+ or z
        assertMatchFails<MissingArgumentException>(params, "a", "StarReqReqStar: One arg")
        assertMatch(params, "a b", mapOf("x" to emptyList(), "y" to listOf("a"), "z" to listOf("b"), "w" to emptyList()), "StarReqReqStar: Two args")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c", "StarReqReqStar: Three args (ambiguity)")
        assertMatchFails<AmbiguousPositionalArgumentException>(params, "a b c d", "StarReqReqStar: Four args (ambiguity)")
    }
}