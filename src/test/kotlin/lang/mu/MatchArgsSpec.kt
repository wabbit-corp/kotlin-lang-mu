package lang.mu

import one.wabbit.data.Either
import one.wabbit.data.Left
import one.wabbit.data.Right
import kotlin.test.Test
import kotlin.test.assertEquals

class ArgMatchingSpec {
    fun parseArgNames(args: String): List<Pair<ArgArity, String>> {
        val args = args.split(Regex("\\s+"))
        val result = mutableListOf<Pair<ArgArity, String>>()
        for (arg in args) {
            if (arg.endsWith("?")) result.add(Pair(ArgArity.Optional, arg.dropLast(1)))
            else if (arg.endsWith("*")) result.add(Pair(ArgArity.ZeroOrMore, arg.dropLast(1)))
            else if (arg.endsWith("+")) result.add(Pair(ArgArity.OneOrMore, arg.dropLast(1)))
            else result.add(Pair(ArgArity.Required, arg))
        }
        return result
    }

    fun parseArgs(args: String): List<Either<String, String>> {
        val args = args.split(Regex("\\s+"))
        val result = mutableListOf<Either<String, String>>()
        for (arg in args) {
            if (arg.startsWith(":")) result.add(Left(arg.drop(1)))
            else result.add(Right(arg))
        }
        return result
    }

    fun assertThrows(f: () -> Any?) {
        try {
            val result = f()
            throw Exception("expected exception, got $result")
        } catch (e: MuException) {
            // OK
        }
    }

    @Test fun `only required parameters`() {
        // (define (f x y z w) ...)
        // (f x y z w)
        // (f :x x :y y :z z :w w) - all arguments named
        // (f :y y x z w)          - BAD, out of order named arguments REQUIRE all subsequent
        //                           arguments to be named
        // (f :y y :x x :z z :w w) - fine, despite out of order args
        // (f :x x y z w)          - fine, since x is first
        // (f :x x y :w w :z z)    - ALSO fine

        val args = parseArgNames("x y z w")

        val result = mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z"), "w" to listOf("w"))

        assertEquals(result, matchArgs(args, parseArgs("x y z w")))
        assertEquals(result, matchArgs(args, parseArgs(":x x :y y :z z :w w")))
        assertThrows { matchArgs(args, parseArgs(":y y x z w")) }
        assertEquals(result, matchArgs(args, parseArgs(":y y :x x :z z :w w")))
        assertEquals(result, matchArgs(args, parseArgs(":x x y z w")))
        assertEquals(result, matchArgs(args, parseArgs(":x x y :w w :z z")))
    }

    @Test fun `vararg at the end`() {
        // (define (f x y z*) ...)
        // (f x y)                 - x=x, y=y, z=[]
        // (f x y z)               - x=x, y=y, z=[z]
        // (f x y z1 z2 z3)        - x=x, y=y, z=[z1, z2, z3]

        val args = parseArgNames("x y z*")

        assertEquals(
            matchArgs(args, parseArgs("x y")),
            mapOf("x" to listOf("x"), "y" to listOf("y")))
        assertEquals(
            matchArgs(args, parseArgs("x y z")),
            mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")))
        assertEquals(
            matchArgs(args, parseArgs("x y z1 z2 z3")),
            mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z1", "z2", "z3")))
    }

    // (define (f x y z+) ...)
    // (f x y)                 - BAD
    // (f x y z)               - x=x, y=y, z=[z]
    // (f x y z1 z2 z3)        - x=x, y=y, z=[z1, z2, z3]
    @Test fun `vararg at the end, required`() {
        val args = parseArgNames("x y z+")

        assertThrows { matchArgs(args, parseArgs("x y")) }
        assertEquals(
            matchArgs(args, parseArgs("x y z")),
            mapOf("x" to listOf("x"), "y" to listOf("y"), "z" to listOf("z")))
        println(matchArgs(args, parseArgs("x y z1 z2 z3")))
    }

    // (define (f x y z?) ...)
    // (f x y)                 - OK, z=null
    // (f x y z)               - OK, z=z
    // (f x y z1 z2 z3)        - BAD, too many arguments
    @Test fun `optional at the end`() {
        val args = parseArgNames("x y z?")

        println(matchArgs(args, parseArgs("x y")))
        println(matchArgs(args, parseArgs("x y z")))
        assertThrows { matchArgs(args, parseArgs("x y z1 z2 z3")) }
    }

    // (define (f x y* z) ...)
    // (f x z)                 - x=x, y=[], z=z
    // (f x y z)               - x=x, y=[y], z=z
    // (f x y1 y2 y3 z)        - x=x, y=[y1, y2, y3], z=z
    @Test fun `vararg in the middle`() {
        val args = parseArgNames("x y* z")

        println(matchArgs(args, parseArgs("x z")))
        // FIXME
//        println(matchArgs(args, parseArgs("x y z")))
//        println(matchArgs(args, parseArgs("x y1 y2 y3 z")))
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
    @Test fun `vararg in the middle, vararg at the end`() {
        val args = parseArgNames("x y* z*")

        println(matchArgs(args, parseArgs("x")))
        // FIXME
        // assertThrows { matchArgs(args, parseArgs("x z1")) }
        // assertThrows { matchArgs(args, parseArgs("x z1 z2")) }
        // assertThrows { matchArgs(args, parseArgs("x z1 z2 z3")) }
        println(matchArgs(args, parseArgs("x :y y")))
        println(matchArgs(args, parseArgs("x :z z")))
        // FIXME
        // assertThrows { matchArgs(args, parseArgs("x :y y1 :y y2 :y y3 z1 z2 z3")) }
        println(matchArgs(args, parseArgs("x :y y1 y2 y3 :z z1 z2 z3")))
        println(matchArgs(args, parseArgs("x :y y1 :y y2 :y y3 :z z1 z2 z3")))
        println(matchArgs(args, parseArgs("x :y y1 :y y2 :y y3 :z z1 :z z2 :z z3")))
    }

    @Test fun `special`() {
        val args = parseArgNames("x y* z* w a")

        // FIXME
        // println(matchArgs(args, parseArgs("x :z z :w w a")))
    }

    @Test fun `regression`() {
        val args = parseArgNames("from preceded-by? not-preceded-by? to probability min-alcohol?")

        //   Caused by wabbit.config.MuException: in
        //   : missing argument: probability, args=[Arg(name=from, quote=false, arity=Required, defaultValue=null), Arg(name=preceded-by, quote=false, arity=Optional, defaultValue=MuValue(unsafeValue=null, type=(kotlin.collections.List kotlin.String))), Arg(name=not-preceded-by, quote=false, arity=Optional, defaultValue=MuValue(unsafeValue=null, type=(kotlin.collections.List kotlin.String))), Arg(name=to, quote=false, arity=Required, defaultValue=null), Arg(name=probability, quote=false, arity=Required, defaultValue=null), Arg(name=min-alcohol, quote=false, arity=Optional, defaultValue=MuValue(unsafeValue=null, type=java.math.BigInteger))]

        println(matchArgs(args, parseArgs(":from x :to y :probability 6 :min-alcohol 80")))
    }
}
