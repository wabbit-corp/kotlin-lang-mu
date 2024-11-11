package lang.mu.std

import lang.mu.std.MuType.Use
import org.slf4j.LoggerFactory
import java.util.*

data class Instance<V>(
    val typeParameters: List<TypeVariable>,
    val parameters: List<MuType.Constructor>,
    val returnType: MuType.Constructor,
    val get: (List<V>) -> V,
) {
    override fun toString(): String {
        return buildString {
            if (typeParameters.isNotEmpty()) {
                append("∀ ")
                append(typeParameters.joinToString(", "))
                append(". ")
            }
            if (parameters.isNotEmpty()) {
                append(parameters.joinToString(", "))
                append(" -> ")
            }
            append(returnType)
        }
    }
}

private val UnificationLogger = LoggerFactory.getLogger("Typing.Unification")
private val ResolutionLogger = LoggerFactory.getLogger("Typing.Resolution")

class TyperState<Value>(
    val instances: Map<String, List<Instance<Value>>> = emptyMap(),
    var nextVar: Int = 0,
    val lattice: MutableEqLattice<TypeVariable, MuType> = MutableEqLattice(),
)
{

    fun copy(): TyperState<Value> =
        TyperState(
            instances,
            nextVar,
            lattice.copy()
        )

    // Unicode Greek Alphabet: αβγδεζηθικλμνξοπρστυφχψω

    fun freshVar(): TypeVariable = TypeVariable("ξ${nextVar++}")
    fun TypeVariable.isVariable(): Boolean = name.startsWith("ξ")

    fun freshExistentialVar(): TypeVariable = TypeVariable("φ${nextVar++}")
    fun TypeVariable.isExistential(): Boolean = name.startsWith("φ")

    /**
     * Compare two types for equality in the current context.
     */
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

    /**
     * Unify two types.
     */
    fun unify(a: MuType, b: MuType) {
        UnificationLogger.debug("unifying: $a ~ $b")

        when (a) {
            is MuType.Constructor -> {
                when (b) {
                    is MuType.Constructor -> unifyS(a, b)
                    is MuType.Forall -> unifyS(b, a)
                    is MuType.Use -> unifyS(b, a)
                    is MuType.Func -> unifyS(b, a)
                    is MuType.Exists -> unifyS(b, a)
                }
            }
            is MuType.Forall -> {
                when (b) {
                    is MuType.Constructor -> unifyS(a, b)
                    is MuType.Forall -> unifyS(a, b)
                    is MuType.Exists -> unifyS(a, b)
                    is MuType.Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                }
            }
            is MuType.Exists -> {
                when (b) {
                    is MuType.Constructor -> unifyS(a, b)
                    is MuType.Forall -> unifyS(a, b)
                    is MuType.Exists -> unifyS(a, b)
                    is MuType.Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                }
            }
            is MuType.Use -> {
                when (b) {
                    is MuType.Constructor -> unifyS(a, b)
                    is MuType.Forall -> unifyS(a, b)
                    is MuType.Exists -> unifyS(a, b)
                    is MuType.Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                }
            }
            is MuType.Func -> {
                when (b) {
                    is MuType.Constructor -> unifyS(a, b)
                    is MuType.Forall -> unifyS(a, b)
                    is MuType.Exists -> unifyS(a, b)
                    is MuType.Use -> unifyS(a, b)
                    is MuType.Func -> unifyS(a, b)
                }
            }
        }
    }

    /**
     * Resolve one or more typeclass instances.
     *
     * The reason we resolve multiple instances at once is to ensure compatibility between the instances,
     * e.g. consider the case of resolving `Show<R>` and `Convert<Int, R>`.
     * On its own, `Show<R>` may have many different instances, but when combined with `Convert<Int, R>`,
     * the number of possible instances is reduced (ideally to one).
     */
    fun resolve(typeclasses: List<MuType.Constructor>): Pair<TyperState<Value>, List<Value>> {
        if (typeclasses.isEmpty()) {
            return this to emptyList()
        }

        val debug = false

        if (debug) println("Resolving: $typeclasses")

//        var goalId = 0

        data class Goal(
            val id: MuType.Constructor,
            val typeclass: MuType.Constructor
        )

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
        ) : Comparable<State> {
            val score: Int = depth + goals.size
            override fun compareTo(other: State): Int = score.compareTo(other.score)
        }

        val queue = PriorityQueue<State>()
        var iterations = 0
        queue.add(State(0, typeclasses.map { makeGoal(it) }, this, emptyMap()))

        while (queue.isNotEmpty() && iterations < 300) {
            iterations += 1
            val (depth, goals, state, accumulated) = queue.poll()
            val prefix = "  ".repeat(depth + 1)

            if (goals.isEmpty()) {
                if (debug) println("${prefix}Found:")
                for ((goal, instance) in accumulated) {
//                    val original = if (goal < typeclasses.size) {
//                        typeclasses[goal]
//                    } else {
//                        null
//                    }
                    if (debug) println("${prefix}  $goal [${instance.type}] -> $instance")
                }

                fun build(goal: MuType.Constructor): Value {
                    val instance = accumulated[goal] ?: error("No instance for goal $goal")
                    val parameters = instance.goals.map { build(it) }
                    return instance.instance.get(parameters)
                }

                val result = typeclasses.map {
                    build(it)
                }

                return state to result

                break
            }

            val sortedGoals = goals.sortedBy {
                it.typeclass.args.size
            }

            for (targetIndex in sortedGoals.indices) {
                val rest = sortedGoals.toMutableList()
                val goal = rest.removeAt(targetIndex)
                if (debug) println("${prefix}Trying goal: ${goal.typeclass}")
                val head = goal.typeclass.head
                val instances = state.instances[head] ?: continue

                val sortedInstances = instances.sortedBy {
                    it.parameters.size
                }

                var errors = mutableListOf<String>()
                var foundOne = false
                for (instance in sortedInstances) {
                    try {
                        val newState = state.copy()
                        val aS = instance.typeParameters.associateWith { Use(freshVar()) }
                        val instanceType = instance.returnType.subst(aS)
                        newState.unify(goal.typeclass, instanceType)

//                        println("${prefix}  instanceType: $instanceType")
//                        println("${prefix}  instance params: ${instance.parameters.map { it.subst(aS) }}")

                        val newGoals = instance.parameters.map { it.subst(aS).subst(newState.lattice) }.map {
                            makeGoal(it as MuType.Constructor)
                        }
                        val newRest = rest.map {
                            val newTC = it.typeclass.subst(newState.lattice) as MuType.Constructor
                            Goal(it.id, newTC)
                        } + newGoals
                        val goalSubst = goal.typeclass.subst(newState.lattice) as MuType.Constructor
                        val newInstance = InstanceWithGoals(goalSubst, instance, newGoals.map { it.id })
                        foundOne = true
                        val newAccumulated = accumulated + (goal.id to newInstance)
                        queue.add(State(depth + 1, newRest, newState, newAccumulated))

                        if (debug) println("${prefix}  unified with ${instance}: ${newState.lattice} ${newAccumulated.map { it.value.type }}")
                        // println("${prefix}New state: ${newState.lattice}")
                    } catch (e: Throwable) {
                        errors.add("${prefix}  $goal ~ ${instance.returnType} : $e")
                        continue
                    }
                }

                if (!foundOne && debug) {
                    println("${prefix}  Errors:")
                    for (error in errors) {
                        println(error)
                    }
                }

                if (debug) println("${prefix}END GOAL: ${goal.typeclass}")
            }
        }

//        println("Solutions: ${solutions.size}")
//        for (s in solutions) {
//            println(s)
//        }

        throw IllegalArgumentException("No solution found for $typeclasses")
    }

    internal fun unifyS(a: MuType.Constructor, b: MuType.Constructor) {
        if (a.head != b.head) {
            throw IllegalArgumentException("head mismatch: ${a.head} != ${b.head}")
        }
        if (a.args.size != b.args.size) {
            throw IllegalArgumentException("arity mismatch: ${a.args} != ${b.args}")
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
    internal fun unifyS(a: MuType.Use, b: MuType) {
        val t = lattice[a.name]
        if (t != null) {
            unify(t, b)
        } else {
            if (b is MuType.Use) {
                lattice.join(a.name, b.name)
            } else {
                lattice[a.name] = b
            }
        }
    }
    internal fun unifyS(a: MuType.Func, b: MuType) {
        if (b is MuType.Func) {
            val aS = a.typeParameters.associateWith { Use(freshVar()) }
            val bS = b.typeParameters.associateWith { Use(freshVar()) }

            if (a.parameters.size != b.parameters.size) {
                throw IllegalArgumentException("parameter arity mismatch: ${a.parameters} != ${b.parameters}")
            }

            for ((a, b) in a.parameters.zip(b.parameters)) {
                unify(a.subst(aS), b.subst(bS))
            }
            unify(a.returnType.subst(aS), b.returnType.subst(bS))
        }
        else {
            throw IllegalArgumentException("type mismatch: $a != $b")
        }
    }
}
