package lang.mu

import lang.mu.std.MuStdValue
import lang.mu.std.MuType
import lang.mu.std.TypeVariable
import lang.mu.std.TyperState
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

    fun assertCantUnify(a: MuType, b: MuType) {
        val state = TyperState<MuStdValue>()
        try {
            state.unify(a, b)
            throw AssertionError("Expected unification to fail, succeeded with: ${state.lattice}")
        } catch (e: Throwable) { }
    }

    fun assertCanUnify(a: MuType, b: MuType) {
        try {
            TyperState<MuStdValue>().unify(a, b)
        } catch (e: Throwable) {
            throw AssertionError("Expected unification to succeed, but failed with: $e", e)
        }
    }

    @Test fun testUnification() {
        assertCanUnify(
            use("a"),
            use("b")
        )
        println()

        assertCanUnify(
            use("a"),
            forall("a", t("Int"))
        )
        println()

        assertCanUnify(
            forall("a", t("Int")),
            forall("a", t("Int"))
        )
        println()

        assertCanUnify(
            forall("a", use("a")),
            forall("b", use("b"))
        )
        println()

        // This represents the identity function type ∀a. a -> a.
        // These are unifiable because they're structurally identical, just with different type variable names.
        assertCanUnify(
            forall("a", func(listOf(use("a")), use("a"))),
            forall("b", func(listOf(use("b")), use("b")))
        )
        assertCanUnify(
            func(listOf("a"), listOf(use("a")), use("a")),
            func(listOf("b"), listOf(use("b")), use("b"))
        )
        println()

        // Here, List<Int> can be unified with List<a> by binding a to Int.
        assertCanUnify(
            t("List", listOf(t("Int"))),
            t("List", listOf(use("a")))
        )
        println()

        // This unifies ∀a. Maybe<a> with ∀b. Maybe<List<b>>, essentially saying that a can be instantiated as List<b>.
        assertCanUnify(
            forall("a", t("Maybe", listOf(use("a")))),
            forall("b", t("Maybe", listOf(t("List", listOf(use("b"))))))
        )
        println()

        // These types have different base constructors and can't be unified.
        assertCantUnify(
            t("Int"),
            t("String")
        )
        println()

        // These function types have different numbers of parameters and can't be unified.
        assertCantUnify(
            func(listOf(t("Int")), t("String")),
            func(listOf(t("Int"), t("Int")), t("String"))
        )
        println()

        // This fails the occurs check, as it would require an infinite type a = List<a>.
        assertCantUnify(
            use("a"),
            t("List", listOf(use("a")))
        )
        println()

        // These represent ∀a. a -> Int and ∀b. b -> b, which have fundamentally different structures.
        assertCantUnify(
            forall("a", func(listOf(use("a")), t("Int"))),
            forall("b", func(listOf(use("b")), use("b")))
        )
        println()

        // This fails because it would require a to be both Int and String simultaneously.
        assertCantUnify(
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
