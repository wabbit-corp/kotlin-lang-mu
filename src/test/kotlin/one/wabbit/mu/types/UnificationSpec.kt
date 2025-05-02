package one.wabbit.mu.types

import one.wabbit.mu.runtime.MuStdValue
import kotlin.test.Test

class UnificationSpec {
    fun forall(v1: String, tpe: MuType) =
        MuType.Forall(listOf(TypeVariable(v1)), tpe)
    fun forall(v1: String, v2: String, tpe: MuType) =
        MuType.Forall(listOf(TypeVariable(v1), TypeVariable(v2)), tpe)
    fun forall(vars: List<String>, tpe: MuType) =
        if (vars.isEmpty()) tpe
        else MuType.Forall(vars.map { TypeVariable(it) }, tpe)
    fun func(typeParameters: List<String>, parameters: List<MuType>, returnType: MuType) =
        MuType.Func(typeParameters.map { TypeVariable(it) }, parameters, returnType)
    fun func(parameters: List<MuType>, returnType: MuType) =
        MuType.Func(emptyList(), parameters, returnType)
    fun t(name: String, args: List<MuType> = emptyList()) =
        MuType.Constructor(name, args)
    fun use(name: String) =
        MuType.Use(TypeVariable(name))

    fun assertUnificationFails(a: MuType, b: MuType) {
        val state = TyperState<MuStdValue>()
        try {
            state.unify(a, b)
            throw AssertionError("Expected unification to fail, succeeded with: ${state.lattice}")
        } catch (e: Throwable) { }
    }

    inline fun <reified E : Throwable> assertUnificationFailsWith(a: MuType, b: MuType) {
        val state = TyperState<MuStdValue>()
        try {
            state.unify(a, b)
            throw AssertionError("Expected unification to fail with ${E::class.simpleName}, but it succeeded. Lattice: ${state.lattice}")
        } catch (e: Throwable) {
            if (e is AssertionError && e.message?.startsWith("Expected unification to fail") == true) throw e // rethrow assertion failure
            if (e !is E) {
                throw AssertionError("Expected unification to fail with ${E::class.simpleName}, but it failed with ${e::class.simpleName}: $e", e)
            }
            println("✅ Unification correctly failed with ${E::class.simpleName}: $a !~ $b")
        }
    }

    // Inside UnificationSpec
    fun assertUnifies(a: MuType, b: MuType, expectedBindings: Map<TypeVariable, MuType>? = null) {
        val state = TyperState<MuStdValue>()
        try {
            state.unify(a, b)
            // Add logic here to check state.lattice against expectedBindings
            // This might involve resolving variables in the expectedBindings through the lattice
            // and comparing them.
            for ((variable, expectedType) in expectedBindings ?: emptyMap()) {
                val actualType = state.lattice[variable] // Or a resolving equivalent
                // Need a robust type equality check here, potentially using state.compare or similar
                if (actualType != expectedType) { // Simplified check
                    throw AssertionError("Expected binding $variable -> $expectedType, but got $actualType. Lattice: ${state.lattice}")
                }
            }
            println("✅ Unification successful and bindings verified: $a ~ $b => $expectedBindings")
        } catch (e: Throwable) {
            throw AssertionError("Expected unification to succeed, but failed with: $e", e)
        }
    }

    @Test fun testUnification() {
        assertUnifies(
            use("a"),
            use("b")
        )
        println()

        assertUnifies(
            use("a"),
            forall("a", t("Int"))
        )
        println()

        assertUnifies(
            forall("a", t("Int")),
            forall("a", t("Int"))
        )
        println()

        assertUnifies(
            forall("a", use("a")),
            forall("b", use("b"))
        )
        println()

        // This represents the identity function type ∀a. a -> a.
        // These are unifiable because they're structurally identical, just with different type variable names.
        assertUnifies(
            forall("a", func(listOf(use("a")), use("a"))),
            forall("b", func(listOf(use("b")), use("b")))
        )
        assertUnifies(
            func(listOf("a"), listOf(use("a")), use("a")),
            func(listOf("b"), listOf(use("b")), use("b"))
        )
        println()

        // Here, List<Int> can be unified with List<a> by binding a to Int.
        assertUnifies(
            t("List", listOf(t("Int"))),
            t("List", listOf(use("a")))
        )
        println()

        // This unifies ∀a. Maybe<a> with ∀b. Maybe<List<b>>, essentially saying that a can be instantiated as List<b>.
        assertUnifies(
            forall("a", t("Maybe", listOf(use("a")))),
            forall("b", t("Maybe", listOf(t("List", listOf(use("b"))))))
        )
        println()

        // These types have different base constructors and can't be unified.
        assertUnificationFails(
            t("Int"),
            t("String")
        )
        println()

        // These function types have different numbers of parameters and can't be unified.
        assertUnificationFails(
            func(listOf(t("Int")), t("String")),
            func(listOf(t("Int"), t("Int")), t("String"))
        )
        println()

        // This fails the occurs check, as it would require an infinite type a = List<a>.
        assertUnificationFails(
            use("a"),
            t("List", listOf(use("a")))
        )
        println()

        // These represent ∀a. a -> Int and ∀b. b -> b, which have fundamentally different structures.
        assertUnificationFails(
            forall("a", func(listOf(use("a")), t("Int"))),
            forall("b", func(listOf(use("b")), use("b")))
        )
        println()

        // This fails because it would require a to be both Int and String simultaneously.
        assertUnificationFails(
            forall("a", func(listOf(use("a"), use("a")), t("Bool"))),
            func(listOf(t("Int"), t("String")), t("Bool"))
        )
        println()

//        val state = TyperState()
//        state.unify(use("a"), forall("a", t("Int")))
//        println(state.equality)
//        println(state.mapping)
//        state.unify(
//            func(listOf("a"), listOf(use("a")), use("a")),
//            func(listOf(t("Int")), use("b")),
//        )
    }
}
