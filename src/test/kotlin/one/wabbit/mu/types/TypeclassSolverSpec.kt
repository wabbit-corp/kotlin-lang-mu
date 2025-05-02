package one.wabbit.mu.types

import one.wabbit.mu.runtime.Mu
import one.wabbit.mu.runtime.MuStdContext
import one.wabbit.mu.runtime.MuStdValue
import one.wabbit.mu.runtime.evaluateMu
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypeclassSolverSpec {
    // --- Solver Error Test Setup ---
    interface RequiresA<T>
    interface RequiresB<T>
    interface CyclicA<T>
    interface CyclicB<T>

    class SolverErrorModule {
        // No instance for RequiresA<Int>

        // Ambiguous (if solver checked for multiple solutions)
        @Mu.Instance val reqB_Int1 = object : RequiresB<Int> {}
        @Mu.Instance val reqB_Int2 = object : RequiresB<Int> {}

        // Cyclic
        @Mu.Instance fun <T> cyclicA(b: CyclicB<T>) = object : CyclicA<T> {}
        @Mu.Instance fun <T> cyclicB(a: CyclicA<T>) = object : CyclicB<T> {}
    }

    @Test
    fun `solver throws InstanceNotFoundException`() {
        val context = MuStdContext.empty()
            .withNativeModule("err_solver", SolverErrorModule())

        val solverState = TyperState<MuStdValue>(context.instances)
        val goal = MuType.Constructor(RequiresA::class.qualifiedName!!, listOf(MuType.Int))

        assertFailsWith<InstanceNotFoundException>(
            "Should throw InstanceNotFoundException when no instance matches"
        ) {
            solverState.resolve(listOf(goal))
        }
    }

    @Test
    fun `solver detects cyclic dependency`() {
        // NOTE: The current fix prunes cyclic paths but throws InstanceNotFound
        // if no non-cyclic path exists. A specific CyclicInstanceDependencyException
        // requires more complex tracking in the solver. This test verifies the *current*
        // behavior (pruning leads to InstanceNotFound).

        val context = MuStdContext.empty()
            .withNativeModule("err_solver", SolverErrorModule())

        val solverState = TyperState<MuStdValue>(context.instances)
        val goalA = MuType.Constructor(CyclicA::class.qualifiedName!!, listOf(MuType.Int))

        // Expect InstanceNotFound because the only path is cyclic and gets pruned
        assertFailsWith<InstanceNotFoundException>(
            "Should fail resolution due to pruned cyclic dependency"
        ) {
            // If CyclicInstanceDependencyException were thrown, we'd catch that instead.
            solverState.resolve(listOf(goalA))
        }
    }

//    // Test for ambiguity is difficult with the current solver strategy
//    // as it takes the first solution found.
//    // @Test
//    // fun `solver throws AmbiguousInstanceException`() { ... }
//
//    interface Show<A> { fun show(a: A): String }
//    class InstanceTestModule {
//        @Mu.Instance val castMuLiteralIntToBigInteger = Upcast.of<MuLiteralInt, BigInteger> { it.value }
//
//        @Mu.Instance val showBigInt = object : Show<BigInteger> {
//            override fun show(a: BigInteger): String = "BigInt($a)"
//        }
//
//        @Mu.Instance fun <A> showList(elemShow: Show<A>) = object : Show<List<A>> {
//            override fun show(a: List<A>): String =
//                a.joinToString(", ", "[", "]") { elemShow.show(it) }
//        }
//
//        // Requires Show<A> implicitly
//        @Mu.Export fun <A> print(@Mu.Name("value") value: A, @Mu.Instance showInstance: Show<A>): String {
//            return "Printing: ${showInstance.show(value)}"
//        }
//    }
//
//    // --- Test Runtime Instance Resolution ---
//    @Test
//    fun `runtime instance resolution works`() {
//        val context = MuStdContext.empty()
//            .withNativeModule("inst", InstanceTestModule())
//            .withOpenModule("inst") // Open module for easier access to 'print'
//
//        var currentCtx = context
//
//        fun eval(expr: String): MuStdValue {
//            val (newCtx, value) = evaluateMu(currentCtx, expr, MuStdContext)
//            currentCtx = newCtx // Update context if needed (though print doesn't modify it)
//            return value
//        }
//
//        // Test with BigInteger (uses specific instance)
//        val result1 = eval("(print 123)") // 123 is MuLiteralInt, upcast to BigInt
//        assertEquals(MuType.String, result1.type)
//        assertEquals("Printing: BigInt(123)", result1.unsafeValue)
//
//        // Test with List<BigInteger> (uses generic instance + specific instance)
//        val result2 = eval("(print [1 2 3])") // [1 2 3] becomes List<MuLiteralInt> -> List<BigInteger>
//        assertEquals(MuType.String, result2.type)
//        assertEquals("Printing: [BigInt(1), BigInt(2), BigInt(3)]", result2.unsafeValue)
//    }
}