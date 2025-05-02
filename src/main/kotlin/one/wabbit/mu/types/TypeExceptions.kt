package one.wabbit.mu.types

import one.wabbit.mu.MuException

/** Base exception for errors during type class instance resolution. */
open class InstanceResolutionException(message: String, cause: Throwable? = null) : MuException(message, cause)

/** Thrown when no suitable instance can be found for a required type class goal. */
class InstanceNotFoundException(
    val goal: MuType.Constructor,
    val context: String? = null // Optional: Add context like which function required it
) : InstanceResolutionException("Instance not found for goal: $goal${context?.let { " (context: $it)" } ?: ""}")

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
