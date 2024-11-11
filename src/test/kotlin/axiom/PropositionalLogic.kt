package axiom

import one.wabbit.data.Either
import one.wabbit.data.Left
import one.wabbit.data.Right
import one.wabbit.random.gen.Gen
import one.wabbit.random.gen.foreach
import kotlin.test.Test
import kotlin.test.assertEquals

sealed interface Prop {
    data class Var(val name: String): Prop

    data class Not(val prop: Prop): Prop

    sealed interface BinOp : Prop {
        val left: Prop
        val right: Prop
    }
    data class And(override val left: Prop, override val right: Prop): BinOp
    data class Or(override val left: Prop, override val right: Prop): BinOp
    data class Imp(override val left: Prop, override val right: Prop): BinOp
    data class Iff(override val left: Prop, override val right: Prop): BinOp

    fun eval(f: (String) -> Boolean): Boolean = when (this) {
        is Var -> f(name)
        is Not -> !prop.eval(f)
        is And -> left.eval(f) && right.eval(f)
        is Or -> left.eval(f) || right.eval(f)
        is Imp -> !left.eval(f) || right.eval(f)
        is Iff -> left.eval(f) == right.eval(f)
    }

    fun partialEval(f: (String) -> Boolean?): Either<Boolean, Prop> = when (this) {
        is Var -> {
            val value = f(name)
            if (value == null) Right(this)
            else Left(value)
        }
        is Not -> {
            val value = prop.partialEval(f)
            when (value) {
                is Left -> Left(!value.value)
                is Right -> Right(Not(value.value))
            }
        }
        is And -> when (val leftValue = left.partialEval(f)) {
            is Left ->
                if (!leftValue.value) Left(false)
                else right.partialEval(f)
            is Right -> when (val rightValue = right.partialEval(f)) {
                is Left -> if (!rightValue.value) Left(false) else leftValue
                is Right -> Right(And(leftValue.value, rightValue.value))
            }
        }
        is Or -> when (val leftValue = left.partialEval(f)) {
            is Left ->
                if (leftValue.value) Left(true)
                else right.partialEval(f)
            is Right -> when (val rightValue = right.partialEval(f)) {
                is Left -> if (rightValue.value) Left(true) else leftValue
                is Right -> Right(Or(leftValue.value, rightValue.value))
            }
        }
        is Imp -> when (val leftValue = left.partialEval(f)) {
            is Left ->
                if (!leftValue.value) Left(true)
                else right.partialEval(f)
            is Right -> when (val rightValue = right.partialEval(f)) {
                is Left -> if (rightValue.value) Left(true) else Left(false)
                is Right -> Right(Imp(leftValue.value, rightValue.value))
            }
        }
        is Iff -> {
            val leftValue = left.partialEval(f)
            val rightValue = right.partialEval(f)

            when (leftValue) {
                is Left -> when (rightValue) {
                    is Left -> Left(leftValue.value == rightValue.value)
                    is Right -> {
                        if (leftValue.value) Right(rightValue.value)
                        else Right(Not(rightValue.value))
                    }
                }
                is Right -> when (rightValue) {
                    is Left -> {
                        if (rightValue.value) Right(leftValue.value)
                        else Right(Not(leftValue.value))
                    }
                    is Right -> Right(Iff(leftValue.value, rightValue.value))
                }
            }
        }
    }

    fun vars(): Set<String> = when (this) {
        is Var -> setOf(name)
        is Not -> prop.vars()
        is BinOp -> left.vars() + right.vars()
    }

    fun depth(): Int = when (this) {
        is Var -> 1
        is Not -> 1 + prop.depth()
        is BinOp -> 1 + maxOf(left.depth(), right.depth())
    }

    fun leaves(): Int = when (this) {
        is Var -> 1
        is Not -> prop.leaves()
        is BinOp -> left.leaves() + right.leaves()
    }

    fun satisfy(): Map<String, Boolean>? {
        data class State(
            val map: Map<String, Boolean>,
            val splits: List<Pair<Prop, Prop>>)

        fun go(lastState: State, prop: Prop): Map<String, Boolean>? {
            val newMap = lastState.map.toMutableMap()
            val newSplits = lastState.splits.toMutableList()

            val queue = ArrayDeque<Prop>()
            queue.add(prop)
            while (queue.isNotEmpty()) {
                val prop = queue.removeFirst()
                when (prop) {
                    is Var -> {
                        val old = newMap[prop.name]
                        if (old == null) {
                            newMap[prop.name] = true
                        } else {
                            return null
                        }
                    }
                    is Not -> {
                        when (prop.prop) {
                            is Var -> {
                                val old = newMap[prop.prop.name]
                                if (old == null) {
                                    newMap[prop.prop.name] = false
                                } else if (old) {
                                    return null
                                }
                            }
                            is Not -> {
                                queue.add(prop.prop.prop)
                            }
                            is And -> {
                                newSplits.add(Not(prop.prop.left) to Not(prop.prop.right))
                            }
                            is Or -> {
                                queue.add(Not(prop.prop.left))
                                queue.add(Not(prop.prop.right))
                            }
                            is Imp -> {
                                queue.add(prop.prop.left)
                                queue.add(Not(prop.prop.right))
                            }
                            is Iff -> {
                                newSplits.add(And(prop.prop.left, Not(prop.prop.right))
                                        to And(Not(prop.prop.left), prop.prop.right))
                            }
                        }
                    }
                    is And -> {
                        queue.add(prop.left)
                        queue.add(prop.right)
                    }
                    is Or -> {
                        newSplits.add(prop.left to prop.right)
                    }
                    is Imp -> {
                        newSplits.add(Not(prop.left) to prop.right)
                    }
                    is Iff -> {
                        newSplits.add(And(prop.left, prop.right) to And(Not(prop.left), Not(prop.right)))
                    }
                }
            }

            if (newSplits.isEmpty()) {
                return newMap
            } else {
                newSplits.sortBy {
                    val (left, right) = it
                    minOf(left.depth(), right.depth())
                }

                val (left, right) = newSplits.removeFirst()
                return if (left.depth() < right.depth()) {
                    go(State(newMap, newSplits), left) ?: go(State(newMap, newSplits), right)
                } else {
                    go(State(newMap, newSplits), right) ?: go(State(newMap, newSplits), left)
                }
            }
        }

        val result = go(State(emptyMap(), emptyList()), this)
        return result
    }

    fun isSatisfiable(): Boolean = satisfy() != null
    fun isFalsifiable(): Boolean = Not(this).isSatisfiable()
    fun isValid(): Boolean = !Not(this).isSatisfiable()
    fun isUnsatisfiable(): Boolean = !isSatisfiable()
}

class PropSpec {
    val genVarName: Gen<String> = Gen.range('a' .. 'z').map { it.toString() }

    val genProp: Gen<Prop> by lazy {
        Gen.freqGen(
            6 to genVarName.map { Prop.Var(it) },
            1 to Gen.delay { genProp.map { Prop.Not(it) } },
            1 to Gen.delay { genProp.flatMap { left -> genProp.map { Prop.And(left, it) } } },
            1 to Gen.delay { genProp.flatMap { left -> genProp.map { Prop.Or(left, it) } } },
            1 to Gen.delay { genProp.flatMap { left -> genProp.map { Prop.Imp(left, it) } } },
            1 to Gen.delay { genProp.flatMap { left -> genProp.map { Prop.Iff(left, it) } } }
        )
    }

    val genAssignmentMap: Gen<Map<String, Boolean>> by lazy {
        Gen.int(0 ..< 10).flatMap { n ->
            Gen.repeat(n, genVarName.flatMap { name ->
                Gen.bool.map { name to it }
            }).map { it.toMap() }
        }
    }

    @Test fun test() {
        genProp.foreach { prop ->
            println("Prop: $prop")
            println("Vars: ${prop.vars()}")
            println("Depth: ${prop.depth()}")
            println("Leaves: ${prop.leaves()}")
            val a = prop.satisfy()
            println("Satisfy: ${a}")
            if (a != null) {
                assertEquals(true, prop.eval { a[it] ?: false })
                assertEquals(true, prop.eval { a[it] ?: true })
                assertEquals(true, prop.partialEval { a[it] } == Left(true))
            }
            println()
        }
    }
}
