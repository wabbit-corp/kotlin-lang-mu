package lang.rho

import one.wabbit.data.Arr
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.Tests
import one.wabbit.random.gen.sampleUnbounded
import one.wabbit.data.shuffled
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val MAX_TEST_ARITY = 2
const val MAX_TEST_TERMS = 4

class PermSpec2 {
    private val rng = SplittableRandom()
    val genConst: Gen<String> =
        Gen.int(0 ..< 10).map { ('a' + it).toString().let {
            if (rng.nextBoolean()) it.intern() else it
        } }
    val genVar: Gen<String> =
        Gen.int(0 ..< 10).map { ('A' + it).toString().intern() }
    val genPredicate: Gen<PredicateName> =
        Gen.int(1 ..< MAX_TEST_ARITY).zip(Gen.int(1 ..< 4))
            .map { (arity, id) ->
                "f${arity}_$id".intern()
            }
    val genArg: Gen<Rho.Arg> = Gen.oneOfGen(
        genVar.map { Rho.Arg.Var(it) },
        genConst.map { Rho.Arg.Const(it) }
    )
    fun genArg1(freeVars: List<String>) =
        if (freeVars.isEmpty()) genConst.map { Rho.Arg.Const(it) }
        else Gen.oneOfGen(
            Gen.int(0 ..< freeVars.size).map { Rho.Arg.Var(freeVars[it]) },
            genConst.map { Rho.Arg.Const(it) }
        )

    fun genTerm(freeVars: List<String>? = null): Gen<Rho.Term> = genPredicate.flatMapZip {
        val arity = it[1] - '0'
        val argGen = if (freeVars == null) genArg else genArg1(freeVars)
        Gen.repeat(arity, argGen)
    }.map { (predicate, args) ->
        Rho.Term(predicate, Arr.fromList(args))
    }

    val genRule: Gen<Rho.Rule> = Gen.int(0 ..< MAX_TEST_TERMS).flatMap { antecedentCount ->
        Gen.int(1 ..< MAX_TEST_TERMS).flatMap { consequentCount ->
            Gen.repeat(antecedentCount, genTerm()).flatMap { antecedents ->
                val freeVars = antecedents.flatMap { it.freeVars() }.toSet()
                Gen.repeat(consequentCount, genTerm(freeVars.toList())).map { consequents ->
                    Rho.Rule(Arr.fromList(antecedents), Arr.fromList(consequents))
                }
            }
        }
    }

    val String.r get() = parseRule(this)
    val String.t get() = parseTerm(this)
    val String.f get() = parseTerm(this).toFact()

    fun printStateProofs(state: Rho) {
        for(f in state.facts.values.sortedBy { it.toString() }) {
            for (it in f.entries.sortedBy { it.key.toString() }) {
                if (it.value.upstreamProofs.size == 1) {
                    val p = it.value.upstreamProofs.entries.first()
                    println("  ${it.key} via ${p.key.id} <- ${p.value.usedFacts.map { it.id }}")
                } else {
                    println("  ${it.key} via")
                    for (p in it.value.upstreamProofs) {
                        println("    ${p.key.id} <- ${p.value.usedFacts.map { it.id }}")
                    }
                }
            }
        }
    }

    @Test fun stressTest() {
        val rng = SplittableRandom(0)
        val state = Rho()
        val t0 = System.nanoTime()
        val TOTAL = 6000
        for (i in 1..TOTAL) {
            if (state.rules.size < 300)
                state.add(genRule.sampleUnbounded(rng))
            else if (state.rules.size > 500)
                state.remove(state.rules.keys.toList()[rng.nextInt(state.rules.size)])
            else {
                if (rng.nextBoolean()) state.add(genRule.sampleUnbounded(rng))
                else state.remove(state.rules.keys.toList()[rng.nextInt(state.rules.size)])
            }
        }
        val t1 = System.nanoTime()

        println("Time: ${(t1 - t0) / 1_000_000.0 / TOTAL} ms per rule")
    }

    @Test fun `adding and removing all rules`() {
        val genRules = Gen.repeat(Gen.int(2 ..< 10), genRule)

        Tests.foreachMin(genRules, SplittableRandom(0), 10000, minimizerSteps = 10000) { rules ->
            val state = Rho()
            for (r in rules) state.add(r)
            for (r in rules) state.remove(r)
            assertTrue(state.rules.isEmpty())
            assertTrue(state.allFacts().toList().isEmpty())
        }
    }

    @Test fun `adding rules in different orders`() {
        val rng = SplittableRandom(0)

        val genRules = Gen.repeat(Gen.int(10 ..< 30), genRule)

        Tests.foreachMin(genRules, rng, 1000) { rules1 ->
            val rng = SplittableRandom(0)
            val rules2 = rules1.shuffled(rng)

            val state1 = Rho()
            for (r in rules1) state1.add(r)

            val state2 = Rho()
            for (r in rules2) state2.add(r)

            assertEquals(state1.rules.size, state2.rules.size)
            assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
        }
    }

    @Test fun `adding and removing rules in different orders`() {
        val rng = SplittableRandom(0)

        val genRules = Gen.repeat(Gen.int(2 ..< 5), genRule)

        Tests.foreachMin(genRules, rng, 1000, minimizerSteps = 10000) { rules1 ->
            val rng = SplittableRandom(0)
            val remove1 = rules1.shuffled(rng).take(rules1.size / 2)
            val rules2 = rules1.shuffled(rng)
            val remove2 = remove1.shuffled(rng)

            val state1 = Rho()
            for (r in rules1) state1.add(r)
            for (r in remove1) state1.remove(r)

            val state2 = Rho()
            for (r in rules2) state2.add(r)
            for (r in remove2) state2.remove(r)

            assertEquals(state1.rules.size, state2.rules.size)
            assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
        }
    }

    @Test fun `adding and removing rules in different orders 2`() {
        val rng = SplittableRandom(0)

        val genRules = Gen.repeat(Gen.int(6 ..< 10), genRule)

        Tests.foreachMin(genRules, rng, 1000, minimizerSteps = 100000) { rules1 ->
            val rng = SplittableRandom(0)
            val remove1 = rules1.take(rules1.size / 2)
            val add1 = rules1.take(rules1.size / 3)
            val remove1_2 = rules1.take(rules1.size / 4)

            val rules2 = rules1.shuffled(rng)
            val remove2 = remove1.shuffled(rng)
            val add2 = add1.shuffled(rng)
            val remove2_2 = remove1_2.shuffled(rng)

            val state1 = Rho()
            for (r in rules1) state1.add(r)
            for (r in remove1) state1.remove(r)
            for (r in add1) state1.add(r)
            for (r in remove1_2) state1.remove(r)

            val state2 = Rho()
            for (r in rules2) state2.add(r)
            for (r in remove2) state2.remove(r)
            for (r in add2) state2.add(r)
            for (r in remove2_2) state2.remove(r)

            assertEquals(state1.rules.size, state2.rules.size)
            assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
        }
    }

    @Test fun `regression 1`() {
        val rules = listOf(
            ":- f".r,
            ":- f ∧ h".r
        )
        val state0 = Rho()
        for (r in rules) state0.add(r)
        for (r in rules) state0.remove(r)
        assertTrue(state0.rules.isEmpty())
        assertTrue(state0.allFacts().toList().isEmpty())
    }

    @Test fun `regression 2`() {
        val state1 = Rho()
        println("####### 1 #######")
        for (r in listOf("x :- y".r, ":- x".r)) state1.add(r)
        println("####### 2 #######")
        state1.remove(":- x".r)
        println(state1.allFacts())

        val state2 = Rho()
        println("####### 3 #######")
        for (r in listOf(":- x".r, "x :- y".r)) state2.add(r)
        println("####### 4 #######")
        state2.remove(":- x".r)
        println(state2.allFacts())

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 3`() {
        //Rules1: [:- f0_1, f0_1 :- f0_1 ∧ f1_2("g"), :- f0_1 ∧ f1_0("a")]
        //Remove1: [:- f0_1]
        //Rules2: [:- f0_1 ∧ f1_0("a"), f0_1 :- f0_1 ∧ f1_2("g"), :- f0_1]
        //Remove2: [:- f0_1]
        val state1 = Rho()
        println("####### 1 #######")
        for (r in listOf(":- x".r, "x :- x ∧ f('g')".r, ":- x ∧ g('a')".r)) state1.add(r)
        printStateProofs(state1)
        assertEquals(setOf(
            "f('g')".f, "x".f, "g('a')".f
        ), state1.allFacts().toSet())

        println("####### 2 #######")
        state1.remove(":- x".r)
        printStateProofs(state1)
        assertEquals(setOf(
            "x".f, "g('a')".f, "f('g')".f
        ), state1.allFacts().toSet())

        val state2 = Rho()
        println("####### 3 #######")
        for (r in listOf(":- x ∧ g('a')".r, "x :- x ∧ f('g')".r, ":- x".r)) state2.add(r)
        printStateProofs(state1)
        println("####### 4 #######")
        state2.remove(":- x".r)
        println(state2.allFacts())

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 4`() {
        // f2_1 :- f1_2, f1_2 ∧ f2_2 :- f1_3 ∧ f2_1, :- f2_2 ∧ f2_1
        val rules1 = listOf(
            "a :- b".r,
            "b ∧ c :- d".r,
            ":- c ∧ a".r
        )

        val rng = SplittableRandom(0)
        val rules2 = rules1.shuffled(rng)

        val state1 = Rho()
        println("####### 1 #######")
        for (r in rules1) state1.add(r)
        println(state1.allFacts())

        val state2 = Rho()
        println("####### 2 #######")
        for (r in rules2) state2.add(r)
        println(state2.allFacts())

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 5`() {
        // f1_2 ∧ f2_1 :- f1_1 ∧ f2_3, :- f1_2, :- f2_2("a") ∧ f1_3, f1_2 :- f1_3 ∧ f1_3
        val rules = listOf(":- a".r, ":- b".r, "a :- b".r)
        val remove1 = listOf(":- a".r, ":- b".r)
        val remove2 = listOf(":- b".r, ":- a".r)

        val state1 = Rho()
        println("####### 1 #######")
        for (r in rules) state1.add(r)
        println("####### 2 #######")
        for (r in remove1) state1.remove(r)
        println(state1.allFacts())

        val state2 = Rho()
        println("####### 3 #######")
        for (r in rules) state2.add(r)
        println("####### 4 #######")
        for (r in remove2) state2.remove(r)
        println(state2.allFacts())

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 6`() {
        // [:- f1_1("g") ∧ f2_2("f", "c"), ∀H, A. f2_2(H, A) :- f1_1("g"), :- f1_1("e")]
        val rules = listOf(":- f(x) ∧ g(z)".r, "g(Z) :- f(x)".r, ":- f(y)".r)

        val state = Rho()
        println("####### 1 #######")
        for (r in rules) state.add(r)
        println("STATE:")
        for(f in state.facts.values) {
            for (it in f) {
                println("  ${it.key} <- ${it.value.upstreamProofs.map{ it.key.id }}")
            }
        }

        println("####### 2 #######")
        for (r in rules) {
            state.remove(r)

            println("STATE:")
            for(f in state.facts.values) {
                for (it in f) {
                    println("  ${it.key} <- ${it.value.upstreamProofs.map{ it.key.id }}")
                }
            }
        }
        println(state.allFacts())
    }

    @Test fun `regression 7`() {
        val rules1 = listOf(
            ":- f('g') ∧ g('b')".r,
            ":- g('a')".r,
            "g(F) :- g('d') ∧ f(F)".r
        )

        val rules2 = listOf(
            ":- f('g') ∧ g('b')".r,
            "g(F) :- g('d') ∧ f(F)".r,
            ":- g('a')".r
        )

        val state1 = Rho()
        for (r in rules1) state1.add(r)
        println("STATE 1:")
        printStateProofs(state1)
        state1.remove(":- g('a')".r)
        // if we remove :- g('a'), the correct set of remaining facts:
        // f('b'), f('d'), f('g'), g('b'), g('d')

        assertEquals(setOf(
            "f('b')".f, "f('d')".f, "f('g')".f, "g('b')".f, "g('d')".f
        ), state1.allFacts().toSet())

        println("STATE 1':")
        printStateProofs(state1)

        val state2 = Rho()
        for (r in rules2) state2.add(r)
        println("STATE 2:")
        printStateProofs(state2)

        state2.remove(":- g('a')".r)
        println("STATE 2':")
        printStateProofs(state2)

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 8`() {
        // :- g("b") ∧ h("i"),
        // ∀J, F. f(J) ∧ f(F) :- f(F),
        // ∀D. h(D) :- f("f") ∧ h("h"),
        // ∀J. h(J) :- f("i"),
        // ∀G. f(G) :- g(G) ∧ h("e")

        val rules1 = listOf(
            ":- g('b') ∧ h('i')".r,
            "f(J) ∧ f(F) :- f(F)".r,
            "h(D) :- f('f') ∧ h('h')".r,
            "h(J) :- f('i')".r,
            "f(G) :- g(G) ∧ h('e')".r
        )

        val rng = SplittableRandom(0)
        val remove1 = rules1.take(rules1.size / 2)
        val add1 = rules1.take(rules1.size / 3)
        val remove1_2 = rules1.take(rules1.size / 4)

        println(remove1)
        println(add1)
        println(remove1_2)

        val rules2 = rules1.shuffled(rng)
        val remove2 = remove1.shuffled(rng)
        val add2 = add1.shuffled(rng)
        val remove2_2 = remove1_2.shuffled(rng)

        val state1 = Rho()
        for (r in rules1) state1.add(r)
        for (r in remove1) state1.remove(r)
        for (r in add1) state1.add(r)
        for (r in remove1_2) state1.remove(r)

        val state2 = Rho()
        for (r in rules2) state2.add(r)
        for (r in remove2) state2.remove(r)
        for (r in add2) state2.add(r)
        for (r in remove2_2) state2.remove(r)

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 9`() {
        // [:- f("e") ∧ g("a"),
        // :- h("b") ∧ h("b"),
        // :- f("i") ∧ g("f"),
        // ∀E, A. g(E) ∧ f(A) :- g(E)]

        val rules1 = listOf(
            ":- f('e') ∧ g('a')".r,
            ":- h('b') ∧ h('b')".r,
            ":- f('i') ∧ g('f')".r,
            "g(E) ∧ f(A) :- g(E)".r
        )

        val rng = SplittableRandom(0)
        val remove1 = rules1.take(rules1.size / 2)
        val add1 = rules1.take(rules1.size / 3)
        val remove1_2 = rules1.take(rules1.size / 4)

        println(remove1) // [:- f("'e'") ∧ g("'a'"), :- h("'b'") ∧ h("'b'")]
        println(add1) // [:- f("'e'") ∧ g("'a'")]
        println(remove1_2) // [:- f("'e'") ∧ g("'a'")]

        val rules2 = rules1.shuffled(rng)
        val remove2 = remove1.shuffled(rng)
        val add2 = add1.shuffled(rng)
        val remove2_2 = remove1_2.shuffled(rng)

        println(rules2) // [:- h("'b'") ∧ h("'b'"), :- f("'i'") ∧ g("'f'"), ∀E, A. g(E) ∧ f(A) :- g(E), :- f("'e'") ∧ g("'a'")]
        println(remove2) // [:- f("'e'") ∧ g("'a'"), :- h("'b'") ∧ h("'b'")]
        println(add2) // [:- f("'e'") ∧ g("'a'")]
        println(remove2_2) // [:- f("'e'") ∧ g("'a'")]

        val state1 = Rho()

        for (r in rules1) state1.add(r)
        println("STATE 1.1:")
        printStateProofs(state1)
        assertEquals(setOf(
            "f('e')".f, "g('a')".f, "h('b')".f, "f('i')".f, "g('f')".f
        ), state1.allFacts().toSet())

        for (r in remove1) state1.remove(r) // [:- f("'e'") ∧ g("'a'"), :- h("'b'") ∧ h("'b'")]
        println("STATE 1.2:")
        printStateProofs(state1)
        assertEquals(setOf(
            "f('i')".f, "g('f')".f
        ), state1.allFacts().toSet())

        for (r in add1) state1.add(r) // [:- f("'e'") ∧ g("'a'")]
        println("STATE 1.3:")
        printStateProofs(state1)
        assertEquals(setOf(
            "f('e')".f, "g('a')".f, "f('i')".f, "g('f')".f
        ), state1.allFacts().toSet())

        for (r in remove1_2) state1.remove(r) // [:- f("'e'") ∧ g("'a'")]
        println("STATE 1.4:")
        printStateProofs(state1)
        assertEquals(setOf(
            "f('i')".f, "g('f')".f
        ), state1.allFacts().toSet())

        val state2 = Rho()
        for (r in rules2) state2.add(r)
        for (r in remove2) state2.remove(r)
        for (r in add2) state2.add(r)
        for (r in remove2_2) state2.remove(r)

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun `regression 10`() {
        // [∀E. f1_2(E) :- f1_3("h"),
        // :- f1_3("e") ∧ f1_2("e"),
        // ∀F. f1_1(F) :- f1_3(F) ∧ f1_3(F),
        // ∀A. f1_3(A) :- f1_1("h")]

        val rules1 = listOf(
            "f(E) :- g('h')".r,
            ":- g('e') ∧ f('e')".r,
            "h(F) :- g(F) ∧ g(F)".r,
            "g(A) :- h('h')".r
        )

        val rng = SplittableRandom(0)
        val remove1 = rules1.take(rules1.size / 2)
        val add1 = rules1.take(rules1.size / 3)
        val remove1_2 = rules1.take(rules1.size / 4)

        println(remove1) // [∀E. f(E) :- g("'h'"), :- g("'e'") ∧ f("'e'")]
        println(add1) // [∀E. f(E) :- g("'h'")]
        println(remove1_2) // [∀E. f(E) :- g("'h'")]

        val rules2 = rules1.shuffled(rng) // [:- g("'e'") ∧ f("'e'"), ∀F. h(F) :- g(F) ∧ g(F), ∀A. g(A) :- h("'h'"), ∀E. f(E) :- g("'h'")]
        val remove2 = remove1.shuffled(rng) // [∀E. f(E) :- g("'h'"), :- g("'e'") ∧ f("'e'")]
        val add2 = add1.shuffled(rng) // [∀E. f(E) :- g("'h'")]
        val remove2_2 = remove1_2.shuffled(rng) // [∀E. f(E) :- g("'h'")]

        println(rules2)
        println(remove2)
        println(add2)
        println(remove2_2)

        val state1 = Rho()
        for (r in rules1) state1.add(r)
        assertEquals(setOf(
            // g('e'), f('e'), h('h'), g('h')
            "g('e')".f, "f('e')".f, "h('h')".f, "g('h')".f
        ), state1.allFacts().toSet())
        println("STATE 1.1:")
        printStateProofs(state1)

        for (r in remove1) state1.remove(r) // // [∀E. f(E) :- g("'h'"), :- g("'e'") ∧ f("'e'")]
        println("STATE 1.2:")
        printStateProofs(state1)
        assertEquals(setOf(), state1.allFacts().toSet())

        for (r in add1) state1.add(r)
        assertEquals(setOf(), state1.allFacts().toSet())

        for (r in remove1_2) state1.remove(r)
        assertEquals(setOf(), state1.allFacts().toSet())

        val state2 = Rho()
        for (r in rules2) state2.add(r)
        for (r in remove2) state2.remove(r)
        for (r in add2) state2.add(r)
        for (r in remove2_2) state2.remove(r)

        assertEquals(state1.rules.size, state2.rules.size)
        assertEquals(state1.allFacts().toSet(), state2.allFacts().toSet())
    }

    @Test fun test() {
        val state = Rho()

        val facts = mutableSetOf<Rho.Fact>()

        state.add(":- group(player1, 'everyone')".r)
        facts.add("group(player1, 'everyone')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add(":- group(player1, 'donator.tier3')".r)
        facts.add("group(player1, 'donator.tier3')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier5') :- group(P, 'donator.tier4')".r)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier4') :- group(P, 'donator.tier3')".r)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier3') :- group(P, 'donator.tier2')".r)
        facts.add("group(player1, 'donator.tier2')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier2') :- group(P, 'donator.tier1')".r)
        facts.add("group(player1, 'donator.tier1')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'everyone') :- permit(P, 'cc.commands.msg') ∧ permit(P, 'cc.commands.reply')".r)
        facts.add("permit(player1, 'cc.commands.msg')".f)
        facts.add("permit(player1, 'cc.commands.reply')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'everyone')      :- permit(P, 'cc.commands.vote')".r)
        facts.add("permit(player1, 'cc.commands.vote')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier1') :- permit(P, 'cc.commands.hat')".r)
        facts.add("permit(player1, 'cc.commands.hat')".f)
        assertEquals(facts, state.allFacts().toSet())

        state.add("group(P, 'donator.tier1') :- permit(P, 'cc.commands.vote')".r)
        assertEquals(facts, state.allFacts().toSet())

        // No-op since the rule was never added.
        state.remove(":- group(player1, 'donator.tier4')".r)
        assertEquals(facts, state.allFacts().toSet())

        state.remove(":- group(player1, 'donator.tier3')".r)
        facts.remove("group(player1, 'donator.tier3')".f)
        facts.remove("group(player1, 'donator.tier2')".f)
        facts.remove("group(player1, 'donator.tier1')".f)
        facts.remove("permit(player1, 'cc.commands.hat')".f)
        assertEquals(facts, state.allFacts().toSet())
    }

    fun parseRule(rule: String): Rho.Rule {
        var parts = rule.split(":-")
        parts = parts.map { it.trim() }
        val leftStr = parts[0]
        val rightStr = parts[1]

        val left = if (leftStr != "") parts[0].split("∧").map { parseTerm(it) } else emptyList()
        val right = if (rightStr != "") parts[1].split("∧").map { parseTerm(it) } else emptyList()
        return Rho.Rule(Arr.fromList(left), Arr.fromList(right))
    }

    fun parseTerm(term: String): Rho.Term {
        // println("Parsing term: $term")
        val term = term.trim()
        if ('(' !in term) {
            return Rho.Term(term, Arr.empty())
        }
        val parts = term.split("(")
        val name = parts[0].trim()
        val argsStr = parts[1].trim().dropLast(1).trim()
        val args = if (argsStr != "") parts[1].trim().dropLast(1).split(",").map { parseArg(it) } else emptyList()
        return Rho.Term(name, Arr.fromList(args))
    }

    fun parseArg(arg: String): Rho.Arg {
        val arg = arg.trim()
        return if (arg[0].isUpperCase()) {
            Rho.Arg.Var(arg)
        } else {
            Rho.Arg.Const(arg)
        }
    }
}
