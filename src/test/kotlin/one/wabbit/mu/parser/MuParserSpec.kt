package one.wabbit.mu.parser

import one.wabbit.math.Rational
import one.wabbit.mu.ast.MuExpr
import one.wabbit.parsing.CharInput
import java.io.File
import java.math.BigInteger
import java.util.SplittableRandom
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class MuParserSpec {
    data class MarkovChain(
        val p1: Map<Char, Double>,
        val p2: Map<Char, Map<Char, Double>>
    ) {
        fun sampleFirst(random: SplittableRandom): Char {
            val r = random.nextDouble()
            var sum = 0.0
            for ((c, p) in p1) {
                sum += p
                if (r < sum) return c
            }
            throw Exception("unreachable")
        }

        fun sampleNext(random: SplittableRandom, c: Char): Char {
            val r = random.nextDouble()
            var sum = 0.0
            for ((c, p) in p2[c]!!) {
                sum += p
                if (r < sum) return c
            }
            throw Exception("unreachable")
        }

        fun sample(random: SplittableRandom): String {
            var c = sampleFirst(random)
            if (c == CharInput.EOB) return ""
            val sb = StringBuilder()
            sb.append(c)

            while (true) {
                c = sampleNext(random, c)
                if (c == CharInput.EOB) break
                sb.append(c)
            }

            return sb.toString()
        }
    }

    private fun buildMarkovChain(strings: List<String>): MarkovChain {
        val alphabet = mutableSetOf<Char>()
        val first_freq = mutableMapOf<Char, Int>()
        val transition_freq = mutableMapOf<Char, MutableMap<Char, Int>>()

        alphabet.add(CharInput.EOB)

        for (s in strings) {
            val first = if (s.isEmpty()) CharInput.EOB else s[0]
            first_freq[first] = first_freq.getOrDefault(first, 0) + 1

            for (i in 0 until s.length) {
                val w1 = s[i]
                val w2 = if (i + 1 < s.length) s[i + 1] else CharInput.EOB

                alphabet.add(w1)

                val map = transition_freq.getOrPut(w1, ::mutableMapOf)
                map[w2] = map.getOrDefault(w2, 0) + 1
            }
        }

        for (c in alphabet) {
            val map = transition_freq.getOrPut(c, ::mutableMapOf)
            for (c in alphabet) map.getOrPut(c) { 0 }
        }

        val first_sum = first_freq.values.sum()
        val p1 = first_freq.mapValues { (_, v) ->
            (v.toDouble() + 0.5) / (first_sum + 0.5 * first_freq.size)
        }

        val p2 = transition_freq.mapValues { (_, freq) ->
            val sum = freq.values.sum()
            freq.mapValues { (_, v) -> (v.toDouble() + 0.5) / (sum + 0.5 * freq.size) }
        }

        return MarkovChain(p1, p2)
    }

    fun p(s: String): MuExpr = MuParser(CharInput.withTextAndPosSpans(s)).parseAll()!![0].lower()
    fun list(vararg s: MuExpr): MuExpr =
        MuExpr.Seq(listOf(MuExpr.Atom("list")) + s.toList())
    fun seq(vararg s: MuExpr): MuExpr =
        MuExpr.Seq(s.toList())
    fun int(i: Int): MuExpr =
        MuExpr.Integer(BigInteger.valueOf(i.toLong()))
    fun atom(s: String): MuExpr =
        MuExpr.Atom(s)
    fun map(vararg s: Pair<MuExpr, MuExpr>): MuExpr =
        seq(atom("map"), *s.map { (k, v) -> seq(atom("pair"), k, v) }.toTypedArray())
    fun str(s: String): MuExpr =
        MuExpr.String(s)

    @Test fun `parsing integers`() {
        assertEquals(MuExpr.Integer(BigInteger.ONE), p("1"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(999)), p("999"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(100_000)), p("100_000"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(10_0000)), p("10_0000"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(1_000_000_000_000_000_000)), p("1_000_000_000_000_000_000"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(-1_000_000_000_000_000_000)), p("-1_000_000_000_000_000_000"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(1_000_000_000_000_000_000)), p("+1_000_000_000_000_000_000"))
        assertEquals(MuExpr.Integer(BigInteger.valueOf(1)), p("0001"))
    }

    @Test fun `parsing reals`() {
        // "1.0", "1.0e10", "1.0e-10", "1.0e+10", "1.0E+10",
        assertEquals(MuExpr.Double(1.0), p("1.0"))
        assertEquals(MuExpr.Double(1.0), p("1.0e0"))
        assertEquals(MuExpr.Double(1.0), p("1.0e+0"))
        assertEquals(MuExpr.Double(1.0), p("1.0e-0"))
        assertEquals(MuExpr.Double(1.0), p("1.0e+00"))
        assertEquals(MuExpr.Double(1.0), p("1.0e-00"))
        assertEquals(MuExpr.Double(2.0), p("2.0"))
        assertEquals(MuExpr.Double(10.0), p("1.0e1"))
        assertEquals(MuExpr.Double(1.0e10), p("1.0e10"))
        assertEquals(MuExpr.Double(1.0e-10), p("1.0e-10"))
        assertEquals(MuExpr.Double(1.0E10), p("1.0E10"))
        assertEquals(MuExpr.Double(1.0E-10), p("1.0E-10"))
        assertEquals(MuExpr.Double(1.0e9), p("1_000_000_000.0"))
        assertEquals(MuExpr.Double(1.0e9), p("1_000_000_000.0e0"))
        assertEquals(MuExpr.Double(1.0e9), p("1_000_000_000.0e+0"))
        //        "1_000.0e-10", "1.0e+01", "001.0e+01",
        assertEquals(MuExpr.Double(1000.0e-10), p("1_000.0e-10"))
        assertEquals(MuExpr.Double(1.0e1), p("1.0e+01"))
        assertEquals(MuExpr.Double(1.0e1), p("001.0e+01"))
        //        "999.9", "-999.9", "+999.9", "0.999_999",
        assertEquals(MuExpr.Double(999.9), p("999.9"))
        assertEquals(MuExpr.Double(-999.9), p("-999.9"))
        assertEquals(MuExpr.Double(+999.9), p("+999.9"))
        assertEquals(MuExpr.Double(0.999_999), p("0.999_999"))
    }

    @Test fun `parsing percentages`() {
        assertEquals(MuExpr.Double(0.0), p("0%"))
        assertEquals(MuExpr.Double(0.0), p("0.0%"))
        assertEquals(MuExpr.Double(0.001), p("0.1%"))
        assertEquals(MuExpr.Double(0.01), p("1%"))
        assertEquals(MuExpr.Double(0.1), p("10%"))
        assertEquals(MuExpr.Double(0.5), p("50%"))
        assertEquals(MuExpr.Double(1.0), p("100%"))
        assertEquals(MuExpr.Double(2.0), p("200.0%"))
    }

    @Test fun `parsing rationals`() {
        // "1/2", "1/2_3", "1/2_3_4", "20/30",
        //        "-1/2", "+1/2",

        assertEquals(MuExpr.Rational(Rational.from(1, 2)), p("1/2"))
        assertEquals(MuExpr.Rational(Rational.from(1, 23)), p("1/2_3"))
        assertEquals(MuExpr.Rational(Rational.from(1, 234)), p("1/2_3_4"))
        assertEquals(MuExpr.Rational(Rational.from(2, 3)), p("20/30"))
        assertEquals(MuExpr.Rational(Rational.from(-1, 2)), p("-1/2"))
        assertEquals(MuExpr.Rational(Rational.from(+1, 2)), p("+1/2"))
    }

    @Test fun `parsing identifiers`() {
        // "a", "ab", "a.b", "a.b.c", "_a", ".a", "a1", "a1_", "a1_2", "abc",
        assertEquals(MuExpr.Atom("a"), p("a"))
        assertEquals(MuExpr.Atom("ab"), p("ab"))
        assertEquals(MuExpr.Atom("a.b"), p("a.b"))
        assertEquals(MuExpr.Atom("a.b.c"), p("a.b.c"))
        assertEquals(MuExpr.Atom("_a"), p("_a"))
        assertEquals(MuExpr.Atom(".a"), p(".a"))
        assertEquals(MuExpr.Atom("a1"), p("a1"))
        assertEquals(MuExpr.Atom("a1_"), p("a1_"))
        assertEquals(MuExpr.Atom("a1_2"), p("a1_2"))
        assertEquals(MuExpr.Atom("abc"), p("abc"))
        //        "@ab", "@ab.cd", "a/b", "a/+",
        assertEquals(MuExpr.Atom("@ab"), p("@ab"))
        assertEquals(MuExpr.Atom("@ab.cd"), p("@ab.cd"))
        assertEquals(MuExpr.Atom("a/b"), p("a/b"))
        assertEquals(MuExpr.Atom("a/+"), p("a/+"))
        //        "+", "=", "<<", "~=", "=>", "->",
        assertEquals(MuExpr.Atom("+"), p("+"))
        assertEquals(MuExpr.Atom("="), p("="))
        assertEquals(MuExpr.Atom("<<"), p("<<"))
        assertEquals(MuExpr.Atom("~="), p("~="))
        assertEquals(MuExpr.Atom("=>"), p("=>"))
        assertEquals(MuExpr.Atom("->"), p("->"))

        // "10h", "10m", "10s", "10ms", "10us", "10ns",
        // "10h10m",
        assertEquals(MuExpr.Atom("10h"), p("10h"))
        assertEquals(MuExpr.Atom("10m"), p("10m"))
        assertEquals(MuExpr.Atom("10s"), p("10s"))
        assertEquals(MuExpr.Atom("10ms"), p("10ms"))
        assertEquals(MuExpr.Atom("10us"), p("10us"))
        assertEquals(MuExpr.Atom("10ns"), p("10ns"))
        assertEquals(MuExpr.Atom("10h10m"), p("10h10m"))
    }

    @Test fun `parsing lists`() {
        // "[]", "[1]", "[1 2]", "[1 2 3]",
        assertEquals(list(), p("[]"))
        assertEquals(list(int(1)), p("[1]"))
        assertEquals(list(int(1), int(2)), p("[1 2]"))
        assertEquals(list(int(1), int(2), int(3)), p("[1 2 3]"))
    }

    @Test fun `parsing seqs`() {
        // "(a)", "(a b)", "(a b c)", "(a (b c))", "(a (b c) d)",
        assertEquals(seq(atom("a")), p("(a)"))
        assertEquals(seq(atom("a"), atom("b")), p("(a b)"))
        assertEquals(seq(atom("a"), atom("b"), MuExpr.Atom("c")), p("(a b c)"))
        assertEquals(seq(atom("a"), seq(atom("b"), atom("c"))), p("(a (b c))"))
        assertEquals(seq(atom("a"), seq(atom("b"), atom("c")), atom("d")), p("(a (b c) d)"))
    }

    @Test fun `parsing maps`() {
        // "{}", "{a: 1, b: 2}", "{a: 1, b: 2,}",
        assertEquals(map(), p("{}"))
        assertEquals(map(atom("a") to int(1), atom("b") to int(2)), p("{a: 1, b: 2}"))
        assertEquals(map(atom("a") to int(1), atom("b") to int(2)), p("{a: 1, b: 2,}"))
        // "{1: 2}", "{1: 2, 3: 4}",
        // FIXME: Parsing { 1: 2 } needs to be fixed
        assertEquals(map(int(1) to int(2)), p("{1 : 2}"))
        assertEquals(map(int(1) to int(2), int(3) to int(4)), p("{1 : 2, 3 : 4}"))
        // "{(f x) : (g y)}",
        assertEquals(map(seq(atom("f"), atom("x")) to seq(atom("g"), atom("y"))), p("{(f x) : (g y)}"))
        // "{[]: []}",
        assertEquals(map(list() to list()), p("{[]: []}"))
        // "(a { b: c })", "{a: (f x)}",
        assertEquals(seq(atom("a"), map(atom("b") to atom("c"))), p("(a { b: c })"))
        assertEquals(map(atom("a") to seq(atom("f"), atom("x"))), p("{a: (f x)}"))
        // """{a: "b"}""", """{a: "b\"c"}""", """{a: "b\nc"}""", """{a: "b\\c"}""", """{a: "b\\\"c"}""",
        assertEquals(map(atom("a") to str("b")), p("""{a: "b"}"""))
        assertEquals(map(atom("a") to str("b\"c")), p("""{a: "b\"c"}"""))
        assertEquals(map(atom("a") to str("b\nc")), p("""{a: "b\nc"}"""))
        assertEquals(map(atom("a") to str("b\\c")), p("""{a: "b\\c"}"""))
    }

    @Test fun `parsing strings`() {
        // "\"\"", "\"a\"", "'a'", "\"a b\"", "'abc'", "'\"'",
        assertEquals(str(""), p("\"\""))
        assertEquals(str("a"), p("\"a\""))
        assertEquals(str("a"), p("'a'"))
        assertEquals(str("a b"), p("\"a b\""))
        assertEquals(str("abc"), p("'abc'"))
        assertEquals(str("\""), p("'\"'"))
    }

    @Test fun `parsing complex`() {
        // "(max-packet-size ((λ (x) (+ x x)) 2))"
        assertEquals(
            seq(
                atom("max-packet-size"),
                seq(
                    seq(
                        atom("λ"),
                        seq(atom("x")),
                        seq(atom("+"), atom("x"), atom("x"))
                    ),
                    int(2)
                )
            ),
            p("(max-packet-size ((λ (x) (+ x x)) 2))")
        )
    }

    @Ignore @Test fun parseSpec() {
        fun p(s: String): MuParsedExpr? {
            try {
                val r = MuParser(CharInput.withTextAndPosSpans(s)).parseAll()
                System.out.println("${s.padEnd(15, ' ')} -> $r")
                System.out.flush()
                return r!![0]
            } catch (e: Exception) {
                System.err.println("${s.padEnd(15, ' ')} -> $e")
                System.err.flush()
                return null
            }
        }

        val examples = mutableListOf<String>()

        for (fn in File("F:\\datatron\\cc-v3\\src\\main\\resources").listFiles()!!) {
            if (!fn.name.endsWith(".rkt")) continue
            val scriptText = fn.readText()
            examples.add(scriptText)
            val scriptTree = try {
                val r = MuParser(CharInput.withTextAndPosSpans(scriptText)).parseAll()
                System.out.println("$fn -> $r")
                System.out.flush()
                r
            } catch (e: Exception) {
                System.err.println("$fn -> $e")
                System.err.flush()
                continue
            }

//            val globalEnv = mutableMapOf<String, MuValue>()
//
//            globalEnv["lambda"] = MuValue.func(MuFunc(
//                name = "lambda",
//                args = listOf(
//                    Arg("params", true, ArgArity.Required, null),
//                    Arg("body", true, ArgArity.Required, null)
//                ),
//                capturedEnv = mapOf(),
//                body = MuFuncBody.Native { penv, env ->
//                    val params = (env["params"] as MuExpr.Seq).value.map { (it as MuExpr.Atom).value }
//                    val body = env["body"] as MuExpr.Seq
//                    val capturedVars = capturedVars(body) - params
//                    MuValue.func(MuFunc(
//                        name = "lambda",
//                        args = params.map { Arg(it, false, ArgArity.Required, null) },
//                        capturedEnv = env.filterKeys { it in capturedVars },
//                        body = MuFuncBody.Expr(body),
//                        unquote = false
//                    ))
//                },
//                unquote = false
//            ))
//
//            // Unicode version
//            globalEnv["λ"] = globalEnv["lambda"]!!
//
//            fun addPrinter(name: String, args: List<String>) {
//                globalEnv[name] = MuValue.func(MuFunc(
//                    name = name,
//                    args = args.map { Arg(it, false, ArgArity.Required, null) },
//                    capturedEnv = mapOf(),
//                    body = MuFuncBody.Native { penv, env ->
//                        println("Printing $name")
//                        println(args.map { env[it] }.joinToString(" "))
//                        MuValue.nil
//                    },
//                    unquote = false
//                ))
//            }
//
//            globalEnv["+"] = MuValue.func(MuFunc(
//                name = "+",
//                args = listOf(
//                    Arg("first", false, ArgArity.Required, null),
//                    Arg("second", false, ArgArity.Required, null)
//                ),
//                capturedEnv = mapOf(),
//                body = MuFuncBody.Native { penv, env ->
//                    val first = (env["first"] as MuExpr.Integer).value
//                    val second = (env["second"] as MuExpr.Integer).value
//                    MuValue.integer(first + second)
//                },
//                unquote = false
//            ))
//
//            globalEnv["define"] = MuValue.func(MuFunc(
//                name = "define",
//                args = listOf(
//                    Arg("params", true, ArgArity.Required, null),
//                    Arg("body", true, ArgArity.Required, null)
//                ),
//                capturedEnv = mapOf(),
//                body = MuFuncBody.Native { penv, env ->
//                    val p = env["params"]?.extract<MuExpr>() ?: throw MuException("Missing parameter list")
//                    val body = env["body"]?.extract<MuExpr>() ?: throw MuException("Missing body")
//                    if (p is MuExpr.Seq) {
//                        val name = (p.value[0] as MuExpr.Atom).value
//                        val params = p.value.drop(1).map { (it as MuExpr.Atom).value }
//                        val capturedVars = capturedVars(body) - params
//
//                        val value = MuValue.func(MuFunc(
//                            name = "lambda",
//                            args = params.map { Arg(it, false, ArgArity.Required, null) },
//                            capturedEnv = env.filterKeys { it in capturedVars },
//                            body = MuFuncBody.Expr(body),
//                            unquote = false
//                        ))
//                        globalEnv[name] = value
//                        return@Native value
//                    } else if (p is MuExpr.Atom) {
//                        val name = (p as MuExpr.Atom).value
//                        val value = runScript(env, body, MuValue.cls)
//                        globalEnv[name] = value
//                        return@Native value
//                    } else {
//                        throw MuException("Invalid parameter list: $p")
//                    }
//                },
//                unquote = false
//            ))
//
//            addPrinter("max-packet-size", listOf("size"))
//            addPrinter("rollbar-token", listOf("token"))
//            addPrinter("server-id", listOf("id"))
////
////            addPrinter("print", listOf("arg"))

//            scriptTree!!
//            for (e in scriptTree)
//                runScript(globalEnv, e.lower(), MuValue.cls)
        }

        val mc = buildMarkovChain(examples)
        val random = SplittableRandom()
        for (it in 1..30) {
            p(mc.sample(random))
        }
    }
}
