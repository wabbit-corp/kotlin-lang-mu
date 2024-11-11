package axiom

import axiom.Prop.*
import kotlin.test.Test

sealed interface Term {
    data class Var(val name: String): Term {
        override fun toString(): String = name
    }

    data class Function(val name: String, val args: List<Term>): Term {
        override fun toString(): String =
            if (args.isNotEmpty()) "$name(${args.joinToString(", ")})"
            else name
    }

    fun freeVariables(): Set<Var> = when (this) {
        is Var -> setOf(this)
        is Function -> args.flatMap { it.freeVariables() }.toSet()
    }

    fun existentialClosure(): Formula {
        val freeVars = freeVariables()
        return if (freeVars.isEmpty()) Formula.Atomic(this)
        else Formula.Exists(freeVars.toList(), Formula.Atomic(this))
    }

    fun universalClosure(): Formula {
        val freeVars = freeVariables()
        return if (freeVars.isEmpty()) Formula.Atomic(this)
        else Formula.ForAll(freeVars.toList(), Formula.Atomic(this))
    }

    fun substitute(substitution: Map<Var, Term>): Term = when (this) {
        is Var -> substitution[this] ?: this
        is Function -> Function(name, args.map { it.substitute(substitution) })
    }
}

sealed interface Formula {
    data class Atomic(val term: Term) : Formula {
        override fun toString(): String {
            return term.toString()
        }
    }
    data class Not(val formula: Formula) : Formula {
        override fun toString(): String {
            return "¬$formula"
        }
    }
    data class And(val left: Formula, val right: Formula) : Formula {
        override fun toString(): String {
            return "($left ∧ $right)"
        }
    }
    data class Or(val left: Formula, val right: Formula) : Formula {
        override fun toString(): String {
            return "($left ∨ $right)"
        }
    }
    data class Implies(val left: Formula, val right: Formula) : Formula {
        override fun toString(): String {
            return "($left → $right)"
        }
    }
    data class ForAll(val variables: List<Term.Var>, val formula: Formula) : Formula {
        override fun toString(): String {
            return "∀${variables.joinToString(", ")}. $formula"
        }
    }
    data class Exists(val variables: List<Term.Var>, val formula: Formula) : Formula {
        override fun toString(): String {
            return "∃${variables.joinToString(", ")}. $formula"
        }
    }

    fun freeVariables(): Set<Term.Var> = when (this) {
        is Atomic -> term.freeVariables()
        is Not -> formula.freeVariables()
        is And -> left.freeVariables() + right.freeVariables()
        is Or -> left.freeVariables() + right.freeVariables()
        is Implies -> left.freeVariables() + right.freeVariables()
        is ForAll -> formula.freeVariables() - variables
        is Exists -> formula.freeVariables() - variables
    }

    fun existentialClosure(): Formula {
        val freeVars = freeVariables()
        return if (freeVars.isEmpty()) this
        else Exists(freeVars.toList(), this)
    }

    fun universalClosure(): Formula {
        val freeVars = freeVariables()
        return if (freeVars.isEmpty()) this
        else ForAll(freeVars.toList(), this)
    }

    fun isClosed(): Boolean = freeVariables().isEmpty()

    fun substitute(substitution: Map<Term.Var, Term>): Formula = when (this) {
        is Atomic -> Atomic(term.substitute(substitution))
        is Not -> Not(formula.substitute(substitution))
        is And -> And(left.substitute(substitution), right.substitute(substitution))
        is Or -> Or(left.substitute(substitution), right.substitute(substitution))
        is Implies -> Implies(left.substitute(substitution), right.substitute(substitution))
        is ForAll -> ForAll(variables, formula.substitute(substitution))
        is Exists -> Exists(variables, formula.substitute(substitution))
    }
}

fun solve(axioms: List<Formula>, result: Formula) {
    println(axioms)
    println(result)
}

class FOLogicSpec {
    @Test fun `test`() {
        val x = Term.Var("x")
        val y = Term.Var("y")
        val z = Term.Var("z")

        val integer = Term.Function("Integer", listOf())
        val real = Term.Function("Real", listOf())
        val string = Term.Function("String", listOf())
        fun numericCast(from: Term, to: Term) = Term.Function("NumericCast", listOf(from, to))
        fun group(t: Term) = Term.Function("Group", listOf(t))
        fun monoid(t: Term) = Term.Function("Monoid", listOf(t))

        val a1 = Formula.ForAll(
            listOf(x), Formula.Atomic(numericCast(x, x))
        )
        val a2 = Formula.ForAll(
            listOf(x, y, z),
            Formula.Implies(
                Formula.And(
                    Formula.Atomic(numericCast(x, y)),
                    Formula.Atomic(numericCast(y, z))
                ),
                Formula.Atomic(numericCast(x, z))
            )
        )
        val a3 = Formula.Atomic(numericCast(integer, real))
        val a4 = Formula.ForAll(
            listOf(x), Formula.Implies(
                Formula.Atomic(group(x)),
                Formula.Atomic(monoid(x))
            )
        )

        val r = Formula.Exists(
            listOf(x), Formula.And(
                Formula.Atomic(monoid(x)),
                Formula.And(
                    Formula.Atomic(numericCast(integer, x)),
                    Formula.Atomic(numericCast(real, x))
                )
            )
        )

        solve(listOf(a1, a2, a3, a4), r)
    }
}
