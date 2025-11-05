package one.wabbit.mu.types

import one.wabbit.mu.MuException
import one.wabbit.mu.types.TypeFormatter.Companion.default as F

/** Base for type inference / unification failures. */
open class TypeInferenceException(message: String, cause: Throwable? = null)
    : MuException(message, cause)

/** Base for unification failures (carries LHS/RHS). */
open class UnificationException(
    val left: MuType,
    val right: MuType,
    message: String
) : TypeInferenceException(message)

/** `Foo[...]` vs `Bar[...]` */
class TypeConstructorHeadMismatchException(
    val expectedHead: String,
    val actualHead: String,
    left: MuType.Constructor,
    right: MuType.Constructor
) : UnificationException(
    left, right,
    "type constructor mismatch: $expectedHead != $actualHead while unifying ${F.format(left)} ~ ${F.format(right)}"
)

/** `Foo[A,B]` vs `Foo[A]` */
class TypeArgumentArityMismatchException(
    val head: String,
    val expected: Int,
    val actual: Int,
    left: MuType.Constructor,
    right: MuType.Constructor
) : UnificationException(
    left, right,
    "type argument arity mismatch for $head: expected $expected, found $actual while unifying ${F.format(left)} ~ ${F.format(right)}"
)

/** `(A,B)->R` vs `(A,B,C)->R` */
class FunctionParameterArityMismatchException(
    val expected: Int,
    val actual: Int,
    val leftFunc: MuType.Func,
    val rightFunc: MuType.Func
) : UnificationException(
    leftFunc, rightFunc,
    "parameter arity mismatch: expected $expected, found $actual while unifying ${F.format(leftFunc)} ~ ${F.format(rightFunc)}"
)

/** Non-function unified with function (or other structural mismatch). */
class TypeMismatchException(
    val expected: MuType,
    val actual: MuType
) : TypeInferenceException(
    "type mismatch: expected ${F.format(expected)}, found ${F.format(actual)}"
)

/** Occurs check (infinite type). */
class OccursCheckException(
    val variable: TypeVariable,
    val inType: MuType
) : TypeInferenceException(
    "occurs check failed: $variable occurs in ${F.format(inType)}"
)

/** Base exception for errors during type class instance resolution. */
open class InstanceResolutionException(message: String, cause: Throwable? = null) : MuException(message, cause)

/** Thrown when no suitable instance can be found for a required type class goal. */
class InstanceNotFoundException(
    val goal: MuType.Constructor,
    val iterations: Int, // Number of resolution iterations attempted
    val maxIterations: Int,
    val context: String? = null // Optional: Add context like which function required it
) : InstanceResolutionException("Instance not found for goal after $iterations/$maxIterations: $goal${context?.let { " (context: $it)" } ?: ""}")

/** Thrown when multiple valid, non-overlapping instances are found for a goal. */
// Note: The current solver uses PriorityQueue and takes the first complete solution,
// so ambiguity might not be directly detected unless explicitly checked for multiple solutions.
// This exception is defined for potential future enhancements or alternative solver strategies.
class AmbiguousInstanceException(
    val goal: MuType.Constructor,
    val instances: List<Instance<*>> // Example: List of potential instances
) : InstanceResolutionException("Ambiguous instance resolution for goal: $goal. Potential instances: $instances")

/** Thrown when a circular dependency is detected during instance resolution. */
class CyclicInstanceDependencyException(
    val goal: MuType.Constructor,
    val path: List<MuType.Constructor> // The resolution path showing the cycle
) : InstanceResolutionException("Cyclic instance dependency detected for goal: $goal. Path: ${path.joinToString(" -> ")}")
