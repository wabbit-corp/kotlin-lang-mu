package one.wabbit.mu.types

import java.util.PriorityQueue
import one.wabbit.mu.types.MuType.Use
import java.util.logging.Level
import java.util.logging.Logger

private val UnificationLogger = Logger.getLogger("Typing.Unification")
private val ResolutionLogger = Logger.getLogger("Typing.Resolution")

class TyperState<Value>(
    val instances: Map<String, List<Instance<Value>>> = emptyMap(),
    var nextVar: Int = 0,
    val lattice: MutableEqLattice<TypeVariable, MuType> = MutableEqLattice(),
) {
    fun copy(): TyperState<Value> = TyperState(instances, nextVar, lattice.copy())

    // Unicode Greek Alphabet: αβγδεζηθικλμνξοπρστυφχψω

    fun freshVar(): TypeVariable = TypeVariable("ξ${nextVar++}")

    fun TypeVariable.isVariable(): Boolean = name.startsWith("ξ")

    fun freshExistentialVar(): TypeVariable = TypeVariable("φ${nextVar++}")

    fun TypeVariable.isExistential(): Boolean = name.startsWith("φ")

    /** Compare two types for equality in the current context. */
    fun compare(a: TypeVariable, b: TypeVariable): TypeComparison {
        if (a == b) {
            return TypeComparison.Equal
        }
        if (a.isExistential() || b.isExistential()) {
            return TypeComparison.Unknown
        }
        if (lattice.compare(a, b)) {
            return TypeComparison.Equal
        } else {
            return TypeComparison.Unknown
        }
    }

    private fun occurs(v: TypeVariable, t: MuType): Boolean =
        when (t) {
            is MuType.Use ->
                // equal or already known-equal via lattice
                v == t.name || (compare(v, t.name) == TypeComparison.Equal)
            is MuType.Constructor -> t.args.any { occurs(v, it) }
            is MuType.Func -> {
                // v bound as a type param? Unlikely (ξ…/φ… vs named), but guard anyway
                if (t.typeParameters.contains(v)) {
                    false
                } else {
                    t.parameters.any { occurs(v, it) } || occurs(v, t.returnType)
                }
            }
            is MuType.Implicit -> {
                // v bound as a type param? Unlikely (ξ…/φ… vs named), but guard anyway
                if (t.typeParameters.contains(v)) {
                    false
                } else {
                    t.parameters.any { occurs(v, it) } || occurs(v, t.returnType)
                }
            }
            is MuType.Forall ->
                // if the binder shadows v, it cannot occur free underneath
                if (t.vars.contains(v)) false else occurs(v, t.tpe)
            is MuType.Exists -> if (t.vars.contains(v)) false else occurs(v, t.tpe)
        }

    /** Unify two types. */
    fun unify(a: MuType, b: MuType) {
        UnificationLogger.log(Level.FINE, "unifying: $a ~ $b")

        // Always open quantifiers first, on either side
        if (a is MuType.Forall) return unifyS(a, b)
        if (b is MuType.Forall) return unifyS(b, a)
        if (a is MuType.Exists) return unifyS(a, b)
        if (b is MuType.Exists) return unifyS(b, a)

        when (a) {
            is MuType.Implicit -> unifyS(a, b)
            is MuType.Constructor -> {
                when (b) {
                    is MuType.Implicit -> unifyS(b, a)
                    is MuType.Constructor -> unifyS(a, b)
                    is Use -> unifyS(b, a)
                    is MuType.Func -> unifyS(b, a)
                    is MuType.Forall -> error("impossible")
                    is MuType.Exists -> error("impossible")
                }
            }
            is MuType.Forall -> error("impossible")
            is MuType.Exists -> error("impossible")
            is Use -> {
                when (b) {
                    is MuType.Implicit -> unifyS(b, a)
                    is MuType.Constructor -> unifyS(a, b)
                    is Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                    is MuType.Forall -> error("impossible")
                    is MuType.Exists -> error("impossible")
                }
            }
            is MuType.Func -> {
                when (b) {
                    is MuType.Implicit -> unifyS(b, a)
                    is MuType.Constructor -> unifyS(a, b)
                    is Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                    is MuType.Forall -> error("impossible")
                    is MuType.Exists -> error("impossible")
                }
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Typeclass resolution
    // //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // --- α-variant keys for goals -------------------------------------------
    // We need a goal key that is stable under:
    //  - current lattice substitution
    //  - α-renaming of both bound *and* free type variables
    // This prevents trivial infinite search via variant cycles:
    //    f A ξ1  ~>  f A ξ2  ~>  ...
    //
    // We treat Func/Forall/Exists as binders and normalise their bound vars.
    private data class GoalKey(val head: String, val args: List<MuType>)

    private fun alphaKey(c: MuType.Constructor): GoalKey {
        // Apply current known equalities first
        val norm = c.subst(lattice)

        // Global renaming env for this normalisation
        val ren: MutableMap<TypeVariable, TypeVariable> = mutableMapOf()
        var next = 0

        fun freshAlpha(): TypeVariable = TypeVariable("α${next++}")

        // Push a list of binder vars with fresh names for the recursive walk.
        fun <T> withBinders(vars: List<TypeVariable>, body: () -> T): T {
            val saved: ArrayList<Pair<TypeVariable, TypeVariable?>> = arrayListOf()
            for (v in vars) {
                val α = freshAlpha()
                saved += v to ren.put(v, α) // store previous mapping (may be null)
            }
            try {
                return body()
            } finally {
                // restore previous mappings to respect shadowing
                for ((v, prev) in saved) {
                    if (prev == null) ren.remove(v) else ren[v] = prev
                }
            }
        }

        fun go(t: MuType): MuType =
            when (t) {
                is MuType.Use ->
                    // Always rename variables (free or bound) to canonical αi
                    MuType.Use(ren.getOrPut(t.name) { freshAlpha() })

                is MuType.Constructor -> MuType.Constructor(t.head, t.args.map(::go))

                is MuType.Forall ->
                    withBinders(t.vars) { MuType.Forall(t.vars.map { ren[it]!! }, go(t.tpe)) }

                is MuType.Exists ->
                    withBinders(t.vars) { MuType.Exists(t.vars.map { ren[it]!! }, go(t.tpe)) }

                is MuType.Func ->
                    withBinders(t.typeParameters) {
                        MuType.Func(
                            t.typeParameters.map { ren[it]!! },
                            t.parameters.map(::go),
                            go(t.returnType),
                        )
                    }

                is MuType.Implicit ->
                    withBinders(t.typeParameters) {
                        MuType.Implicit(
                            t.typeParameters.map { ren[it]!! },
                            t.parameters.map { go(it) as MuType.Constructor },
                            go(t.returnType),
                        )
                    }
            }

        val n = go(norm) as MuType.Constructor
        return GoalKey(n.head, n.args)
    }

    /**
     * Resolve one or more typeclass instances.
     *
     * The reason we resolve multiple instances at once is to ensure compatibility between the
     * instances, e.g. consider the case of resolving `Show<R>` and `Convert<Int, R>`. On its own,
     * `Show<R>` may have many different instances, but when combined with `Convert<Int, R>`, the
     * number of possible instances is reduced (ideally to one).
     */
    fun resolve(
        typeclasses: List<MuType.Constructor>,
        maxIterations: Int = 300,
    ): Pair<TyperState<Value>, List<Value>> {
        if (typeclasses.isEmpty()) {
            return this to emptyList()
        }

        val debug = true

        val F = TypeFormatter.default

        fun MuType.format(): String = F.format(this)

        ResolutionLogger.info("Resolving: ${typeclasses.joinToString(", ") { it.format() }}")

        //        var goalId = 0

        data class Goal(val id: MuType.Constructor, val typeclass: MuType.Constructor)

        fun makeGoal(typeclass: MuType.Constructor): Goal {
            //            val id = goalId++
            return Goal(typeclass, typeclass)
        }

        data class InstanceWithGoals(
            val type: MuType.Constructor,
            val instance: Instance<Value>,
            val goals: List<MuType.Constructor>,
        )

        data class State(
            val depth: Int,
            val goals: List<Goal>,
            val state: TyperState<Value>,
            val accumulated: Map<MuType.Constructor, InstanceWithGoals>,
            val ancestors: Set<GoalKey>,
        ) : Comparable<State> {
            val score: Int = depth + goals.size

            override fun compareTo(other: State): Int = score.compareTo(other.score)
        }

        val queue = PriorityQueue<State>()
        // nogood cache of α-variant goals known to be unsatisfiable under current lattice
        val nogoods = mutableSetOf<GoalKey>()
        var iterations = 0
        queue.add(State(0, typeclasses.map { makeGoal(it) }, this, emptyMap(), emptySet()))

        while (queue.isNotEmpty() && iterations < maxIterations) {
            iterations += 1
            val state = queue.poll()
            val (depth, goals, typerState, accumulated, _) = state
            val prefix = "  ".repeat(depth + 1)

            if (goals.isEmpty()) {
                ResolutionLogger.info("${prefix}Found:")
                for ((goal, instance) in accumulated) {
                    //                    val original = if (goal < typeclasses.size) {
                    //                        typeclasses[goal]
                    //                    } else {
                    //                        null
                    //                    }
                    ResolutionLogger.info(
                        "$prefix  ${goal.format()} [${instance.type.format()}] -> $instance"
                    )
                }

                fun build(goal: MuType.Constructor): Value {
                    val instance = accumulated[goal] ?: error("No instance for goal $goal")
                    val parameters = instance.goals.map { build(it) }
                    return instance.instance.get(parameters)
                }

                val result = typeclasses.map { build(it) }

                return typerState to result

                break
            }

            // ---- helper: count open vars (after current substitution), for better goal ordering
            fun varCount(t: MuType): Int =
                when (t) {
                    is MuType.Use -> 1
                    is MuType.Constructor -> t.args.sumOf(::varCount)
                    is MuType.Forall -> varCount(t.tpe)
                    is MuType.Exists -> varCount(t.tpe)
                    is MuType.Func ->
                        t.typeParameters.size +
                            t.parameters.sumOf(::varCount) +
                            varCount(t.returnType)
                    is MuType.Implicit ->
                        t.typeParameters.size +
                            t.parameters.sumOf(::varCount) +
                            varCount(t.returnType)
                }

            // ---- helper: α-dedupe a list of goals under the given state
            fun dedupAlpha(gs: List<Goal>, s: TyperState<Value>): List<Goal> {
                val seen = HashSet<GoalKey>()
                val out = ArrayList<Goal>(gs.size)
                for (g in gs) {
                    val k = s.alphaKey(g.typeclass)
                    if (seen.add(k)) out += g
                }
                return out
            }
            // Prefer more instantiated goals; also drop in-state α-duplicates up front.
            val uniqueGoals = dedupAlpha(goals, typerState)
            val sortedGoals =
                uniqueGoals.sortedWith(
                    compareBy<Goal> { varCount(it.typeclass.subst(typerState.lattice)) }
                        .thenBy { it.typeclass.args.size }
                )

            for (targetIndex in sortedGoals.indices) {
                val rest = sortedGoals.toMutableList()
                val goal = rest.removeAt(targetIndex)
                ResolutionLogger.info("${prefix}Trying goal: ${goal.typeclass.format()}")
                val head = goal.typeclass.head
                val instances = typerState.instances[head] ?: continue

                // α-key of the selected goal
                val selKey = typerState.alphaKey(goal.typeclass)
                // If we somehow see an ancestor-variant again at the same node, skip immediately
                if (selKey in state.ancestors) {
                    ResolutionLogger.info(
                        "$prefix  Skipping goal (ancestor variant): ${goal.typeclass.format()}"
                    )
                    continue
                }

                // Skip states whose selected goal is already known to be impossible.
                val gKey = selKey
                if (gKey in nogoods) {
                    ResolutionLogger.info(
                        "$prefix  Skipping goal (nogood): ${goal.typeclass.format()}"
                    )
                    continue
                }

                val sortedInstances = instances.sortedBy { it.parameters.size }

                var errors = mutableListOf<String>()
                var foundOne = false
                for (instance in sortedInstances) {
                    try {
                        val newState = typerState.copy()
                        val aS = instance.typeParameters.associateWith { Use(freshVar()) }
                        val instanceType = instance.returnType.subst(aS)
                        newState.unify(goal.typeclass, instanceType)

                        //                        println("${prefix}  instanceType: $instanceType")
                        //                        println("${prefix}  instance params:
                        // ${instance.parameters.map { it.subst(aS) }}")

                        val newGoals =
                            instance.parameters
                                .map { it.subst(aS).subst(newState.lattice) }
                                .map { makeGoal(it as MuType.Constructor) }

                        val newRest0 =
                            rest.map {
                                val newTC =
                                    it.typeclass.subst(newState.lattice) as MuType.Constructor
                                Goal(it.id, newTC)
                            } + newGoals

                        // Drop α-duplicate goals *inside the child state* to prevent fan-out
                        val newRest = dedupAlpha(newRest0, newState)

                        // --- STRONGER: α-variant loop check with full ancestry set
                        // Do not enqueue if the child would (re)introduce any α-variant of a goal
                        // on the path.
                        val childAncestors = state.ancestors + selKey
                        val anyLoop =
                            newRest.any { newState.alphaKey(it.typeclass) in childAncestors }
                        if (anyLoop) {
                            ResolutionLogger.info(
                                "$prefix  Pruned by α-variant loop check (ancestor)"
                            )
                            continue
                        }

                        val goalSubst = goal.typeclass.subst(newState.lattice) as MuType.Constructor
                        val newInstance =
                            InstanceWithGoals(goalSubst, instance, newGoals.map { it.id })
                        foundOne = true
                        val newAccumulated = accumulated + (goal.id to newInstance)
                        queue.add(
                            State(depth + 1, newRest, newState, newAccumulated, childAncestors)
                        )

                        ResolutionLogger.info(
                            "$prefix  unified with $instance: ${newState.lattice} ${newAccumulated.map { it.value.type }}"
                        )
                        // println("${prefix}New state: ${newState.lattice}")
                    } catch (e: Throwable) {
                        errors.add("$prefix  $goal ~ ${instance.returnType.format()} : $e")
                        continue
                    }
                }

                if (!foundOne && debug) {
                    ResolutionLogger.info("$prefix  Errors: ${errors.joinToString(", ")}")
                }

                // If no instance worked for this selected goal, remember it as a nogood.
                if (!foundOne) {
                    nogoods.add(gKey)
                }

                ResolutionLogger.info("${prefix}END GOAL: ${goal.typeclass.format()}")
            }
        }

        //        println("Solutions: ${solutions.size}")
        //        for (s in solutions) {
        //            println(s)
        //        }

        // If queue is empty and no solution was found
        if (iterations >= maxIterations) {
            ResolutionLogger.log(Level.WARNING,
                "Instance resolution exceeded max iterations ($maxIterations) for goals: ${typeclasses.joinToString(
                    ", "
                ) { it.format() }}}"
            )
        } else {
            ResolutionLogger.log(Level.WARNING,
                "Instance resolution failed for goals: ${typeclasses.joinToString(", ") { it.format() }}"
            )
        }

        // We need to know which goal failed. This requires more sophisticated tracking
        // or analyzing the remaining states in the queue if it wasn't empty.
        // For now, throw a general error.
        throw InstanceNotFoundException(
            typeclasses.first(),
            iterations,
            maxIterations,
        ) // Report failure on the first goal as a starting point
    }

    internal fun unifyS(a: MuType.Constructor, b: MuType.Constructor) {
        if (a.head != b.head) {
            throw TypeConstructorHeadMismatchException(a.head, b.head, a, b)
        }
        if (a.args.size != b.args.size) {
            throw TypeArgumentArityMismatchException(a.head, a.args.size, b.args.size, a, b)
        }
        for ((a, b) in a.args.zip(b.args)) {
            unify(a, b)
        }
    }

    internal fun unifyS(a: MuType.Forall, b: MuType) {
        val aS = a.vars.associateWith { Use(freshExistentialVar()) }
        val t = a.tpe.subst(aS)
        unify(b, t)
    }

    internal fun unifyS(a: MuType.Exists, b: MuType) {
        val aS = a.vars.associateWith { Use(freshVar()) }
        val t = a.tpe.subst(aS)
        unify(b, t)
    }

    internal fun unifyS(a: Use, b: MuType) {
        val t = lattice[a.name]
        if (t != null) {
            unify(t, b)
        } else {
            if (b is Use) {
                lattice.join(a.name, b.name)
            } else {
                if (occurs(a.name, b)) {
                    throw OccursCheckException(a.name, b)
                }
                lattice[a.name] = b
            }
        }
    }

    internal fun unifyS(a: MuType.Func, b: MuType) {
        if (b is MuType.Func) {
            val aS = a.typeParameters.associateWith { Use(freshVar()) }
            val bS = b.typeParameters.associateWith { Use(freshVar()) }

            if (a.parameters.size != b.parameters.size) {
                throw FunctionParameterArityMismatchException(
                    a.parameters.size,
                    b.parameters.size,
                    a,
                    b,
                )
            }

            for ((a, b) in a.parameters.zip(b.parameters)) {
                unify(a.subst(aS), b.subst(bS))
            }
            unify(a.returnType.subst(aS), b.returnType.subst(bS))
        } else {
            throw TypeMismatchException(a, b) // message contains "type mismatch"
        }
    }

    internal fun unifyS(a: MuType.Implicit, b: MuType) {
        val σ = a.typeParameters.associateWith { MuType.Use(freshExistentialVar()) }
        // DO NOT accumulate constraints here
        unify(a.returnType.subst(σ), b)
    }
}
