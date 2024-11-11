//package lang.mu
//
//import kotlin.collections.reduce
//
//// Terms
//
//sealed class Term {
//    abstract val time: Int
//    abstract fun freeVariables(): Set<Variable>
//    abstract fun freeUnificationTerms(): Set<UnificationTerm>
//    abstract fun replace(old: Term, new: Term): Term
//    abstract fun occurs(unificationTerm: UnificationTerm): Boolean
//    abstract fun withInstantiationTime(time: Int): Term
//}
//
//data class Variable(val name: String, override val time: Int) : Term() {
//    override fun freeVariables(): Set<Variable> = setOf(this)
//    override fun freeUnificationTerms(): Set<UnificationTerm> = emptySet()
//    override fun replace(old: Term, new: Term): Term = if (this == old) new else this
//    override fun occurs(unificationTerm: UnificationTerm): Boolean = false
//    override fun withInstantiationTime(time: Int): Variable = Variable(name, time)
//    override fun toString(): String = name
//}
//
//data class UnificationTerm(val name: String, override val time: Int) : Term() {
//    override fun freeVariables(): Set<Variable> = emptySet()
//    override fun freeUnificationTerms(): Set<UnificationTerm> = setOf(this)
//    override fun replace(old: Term, new: Term): Term = if (this == old) new else this
//    override fun occurs(unificationTerm: UnificationTerm): Boolean = this == unificationTerm
//    override fun withInstantiationTime(time: Int): UnificationTerm = UnificationTerm(name, time)
//    override fun toString(): String = name
//}
//
//data class Function(val name: String, val terms: List<Term>, override val time: Int = 0) : Term() {
//    override fun freeVariables(): Set<Variable> =
//        terms.flatMap { it.freeVariables() }.toSet()
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        terms.flatMap { it.freeUnificationTerms() }.toSet()
//    override fun replace(old: Term, new: Term): Term =
//        if (this == old) new else Function(name, terms.map { it.replace(old, new) })
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        terms.any { it.occurs(unificationTerm) }
//    override fun withInstantiationTime(time: Int): Function =
//        Function(name, terms.map { it.withInstantiationTime(time) }, time)
//    override fun toString(): String =
//        if (terms.isEmpty()) name else "$name(${terms.joinToString(", ")})"
//}
//
//// Formulae
//
//sealed class Formula {
//    abstract fun freeVariables(): Set<Variable>
//    abstract fun freeUnificationTerms(): Set<UnificationTerm>
//    abstract fun replace(old: Term, new: Term): Formula
//    abstract fun occurs(unificationTerm: UnificationTerm): Boolean
//    abstract fun withInstantiationTime(time: Int): Formula
//}
//
//data class Predicate(val name: String, val terms: List<Term>) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        terms.flatMap { it.freeVariables() }.toSet()
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        terms.flatMap { it.freeUnificationTerms() }.toSet()
//    override fun replace(old: Term, new: Term): Predicate =
//        Predicate(name, terms.map { it.replace(old, new) })
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        terms.any { it.occurs(unificationTerm) }
//    override fun withInstantiationTime(time: Int): Predicate =
//        Predicate(name, terms.map { it.withInstantiationTime(time) })
//    override fun toString(): String =
//        if (terms.isEmpty()) name else "$name(${terms.joinToString(", ")})"
//}
//
//data class Not(val formula: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> = formula.freeVariables()
//    override fun freeUnificationTerms(): Set<UnificationTerm> = formula.freeUnificationTerms()
//    override fun replace(old: Term, new: Term): Not = Not(formula.replace(old, new))
//    override fun occurs(unificationTerm: UnificationTerm): Boolean = formula.occurs(unificationTerm)
//    override fun withInstantiationTime(time: Int): Not = Not(formula.withInstantiationTime(time))
//    override fun toString(): String = "¬$formula"
//}
//
//data class And(val formulaA: Formula, val formulaB: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        formulaA.freeVariables() union formulaB.freeVariables()
//
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        formulaA.freeUnificationTerms() union formulaB.freeUnificationTerms()
//
//    override fun replace(old: Term, new: Term): And =
//        And(formulaA.replace(old, new), formulaB.replace(old, new))
//
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        formulaA.occurs(unificationTerm) || formulaB.occurs(unificationTerm)
//
//    override fun withInstantiationTime(time: Int): And =
//        And(formulaA.withInstantiationTime(time),
//            formulaB.withInstantiationTime(time))
//
//    override fun toString(): String = "($formulaA ∧ $formulaB)"
//}
//
//data class Or(val formulaA: Formula, val formulaB: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        formulaA.freeVariables() union formulaB.freeVariables()
//
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        formulaA.freeUnificationTerms() union formulaB.freeUnificationTerms()
//
//    override fun replace(old: Term, new: Term): Or =
//        Or(formulaA.replace(old, new), formulaB.replace(old, new))
//
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        formulaA.occurs(unificationTerm) || formulaB.occurs(unificationTerm)
//
//    override fun withInstantiationTime(time: Int): Or =
//        Or(formulaA.withInstantiationTime(time),
//            formulaB.withInstantiationTime(time))
//
//    override fun toString(): String = "($formulaA ∨ $formulaB)"
//}
//
//data class Implies(val formulaA: Formula, val formulaB: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        formulaA.freeVariables() union formulaB.freeVariables()
//
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        formulaA.freeUnificationTerms() union formulaB.freeUnificationTerms()
//
//    override fun replace(old: Term, new: Term): Implies =
//        Implies(formulaA.replace(old, new), formulaB.replace(old, new))
//
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        formulaA.occurs(unificationTerm) || formulaB.occurs(unificationTerm)
//
//    override fun withInstantiationTime(time: Int): Implies =
//        Implies(formulaA.withInstantiationTime(time),
//            formulaB.withInstantiationTime(time))
//
//    override fun toString(): String = "($formulaA → $formulaB)"
//}
//
//data class ForAll(val variable: Variable, val formula: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        formula.freeVariables() - variable
//
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        formula.freeUnificationTerms()
//
//    override fun replace(old: Term, new: Term): ForAll =
//        ForAll(variable.replace(old, new) as Variable, formula.replace(old, new))
//
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        formula.occurs(unificationTerm)
//
//    override fun withInstantiationTime(time: Int): ForAll =
//        ForAll(variable.withInstantiationTime(time), formula.withInstantiationTime(time))
//
//    override fun toString(): String = "(∀$variable. $formula)"
//}
//
//data class ThereExists(val variable: Variable, val formula: Formula) : Formula() {
//    override fun freeVariables(): Set<Variable> =
//        formula.freeVariables() - variable
//
//    override fun freeUnificationTerms(): Set<UnificationTerm> =
//        formula.freeUnificationTerms()
//
//    override fun replace(old: Term, new: Term): ThereExists =
//        ThereExists(variable.replace(old, new) as Variable, formula.replace(old, new))
//
//    override fun occurs(unificationTerm: UnificationTerm): Boolean =
//        formula.occurs(unificationTerm)
//
//    override fun withInstantiationTime(time: Int): ThereExists =
//        ThereExists(
//            variable.withInstantiationTime(time),
//            formula.withInstantiationTime(time))
//
//    override fun toString(): String = "(∃$variable. $formula)"
//}
//
//
//// Unification
//
//// solve a single equation
//fun unify(termA: Formula, termB: Formula): Map<UnificationTerm, Term>? {
//    when {
//        termA is UnificationTerm -> {
//            if (termB.occurs(termA) || termB.time > termA.time) {
//                return null
//            }
//            return mapOf(termA to termB)
//        }
//        termB is UnificationTerm -> {
//            if (termA.occurs(termB) || termA.time > termB.time) {
//                return null
//            }
//            return mapOf(termB to termA)
//        }
//        termA is Variable && termB is Variable -> {
//            return if (termA == termB) emptyMap() else null
//        }
//        (termA is Function && termB is Function) -> {
//            if (termA.name != termB.name || termA.terms.size != termB.terms.size) {
//                return null
//            }
//            val substitution = mutableMapOf<UnificationTerm, Term>()
//            for (i in termA.terms.indices) {
//                var a = termA.terms[i]
//                var b = termB.terms[i]
//                for ((k, v) in substitution) {
//                    a = a.replace(k, v)
//                    b = b.replace(k, v)
//                }
//                val sub = unify(a, b) ?: return null
//                substitution.putAll(sub)
//            }
//            return substitution
//        }
//        else -> return null
//    }
//}
//
//// solve a list of equations
//fun unifyList(pairs: List<Pair<Term, Term>>): Map<UnificationTerm, Term>? {
//    val substitution = mutableMapOf<UnificationTerm, Term>()
//    for ((termA, termB) in pairs) {
//        var a = termA
//        var b = termB
//        for ((k, v) in substitution) {
//            a = a.replace(k, v)
//            b = b.replace(k, v)
//        }
//        val sub = unify(a, b) ?: return null
//        substitution.putAll(sub)
//    }
//    return substitution
//}
//
//// Sequents
//
//data class Sequent(
//    val left: Map<Term, Int>,
//    val right: Map<Term, Int>,
//    var siblings: MutableSet<Sequent>?,
//    val depth: Int
//) {
//    fun freeVariables(): Set<Variable> {
//        val result = mutableSetOf<Variable>()
//        left.keys.forEach { result.addAll(it.freeVariables()) }
//        right.keys.forEach { result.addAll(it.freeVariables()) }
//        return result
//    }
//
//    fun freeUnificationTerms(): Set<UnificationTerm> {
//        val result = mutableSetOf<UnificationTerm>()
//        left.keys.forEach { result.addAll(it.freeUnificationTerms()) }
//        right.keys.forEach { result.addAll(it.freeUnificationTerms()) }
//        return result
//    }
//
//    fun getVariableName(prefix: String): String {
//        val fv = freeVariables() + freeUnificationTerms()
//        var index = 1
//        var name = prefix + index
//        while (Variable(name, 0) in fv || UnificationTerm(name, 0) in fv) {
//            index++
//            name = prefix + index
//        }
//        return name
//    }
//
//    fun getUnifiablePairs(): List<Pair<Term, Term>> {
//        return left.keys.flatMap { formulaLeft ->
//            right.keys.mapNotNull { formulaRight ->
//                if (unify(formulaLeft, formulaRight) != null) {
//                    formulaLeft to formulaRight
//                } else null
//            }
//        }
//    }
//
//    override fun toString(): String {
//        val leftPart = left.keys.joinToString(", ")
//        val rightPart = right.keys.joinToString(", ")
//        return "${if (leftPart.isNotEmpty()) "$leftPart " else ""}⊢${if (rightPart.isNotEmpty()) " $rightPart" else ""}"
//    }
//}
//
//// Proof search
//
//// returns true if the sequent is provable
//// returns false or loops forever if the sequent is not provable
//fun proveSequent(sequent: Sequent): Boolean {
//    // reset the time for each formula in the sequent
//    sequent.left.keys.forEach { it.setInstantiationTime(0) }
//    sequent.right.keys.forEach { it.setInstantiationTime(0) }
//
//    // sequents to be proven
//    val frontier = mutableListOf(sequent)
//
//    // sequents which have been proven
//    val proven = mutableSetOf(sequent)
//
//    while (true) {
//        // get the next sequent
//        var oldSequent: Sequent? = null
//        while (frontier.isNotEmpty() && (oldSequent == null || oldSequent in proven)) {
//            oldSequent = frontier.removeAt(0)
//        }
//        if (oldSequent == null) break
//        println("${oldSequent.depth}. $oldSequent")
//
//        // check if this sequent is axiomatically true without unification
//        if (oldSequent.left.keys.intersect(oldSequent.right.keys).isNotEmpty()) {
//            proven.add(oldSequent)
//            continue
//        }
//
//        // check if this sequent has unification terms
//        if (oldSequent.siblings != null) {
//            // get the unifiable pairs for each sibling
//            val siblingPairLists = oldSequent.siblings!!.map { it.getUnifiablePairs() }
//
//            // check if there is a unifiable pair for each sibling
//            if (siblingPairLists.all { it.isNotEmpty() }) {
//                // iterate through all simultaneous choices of pairs from each sibling
//                var substitution: Map<UnificationTerm, Term>? = null
//                val index = IntArray(siblingPairLists.size)
//                while (true) {
//                    // attempt to unify at the index
//                    substitution = unifyList(siblingPairLists.mapIndexed { i, list -> list[index[i]] })
//                    if (substitution != null) break
//
//                    // increment the index
//                    var pos = siblingPairLists.size - 1
//                    while (pos >= 0) {
//                        index[pos]++
//                        if (index[pos] < siblingPairLists[pos].size) break
//                        index[pos] = 0
//                        pos--
//                    }
//                    if (pos < 0) break
//                }
//                if (substitution != null) {
//                    for ((k, v) in substitution) {
//                        println("  $k = $v")
//                    }
//                    proven.addAll(oldSequent.siblings!!)
//                    frontier.removeAll(oldSequent.siblings!!)
//                    continue
//                }
//            } else {
//                // unlink this sequent
//                oldSequent.siblings!!.remove(oldSequent)
//            }
//        }
//
//        while (true) {
//            // determine which formula to expand
//            val leftFormula = oldSequent.left.entries.filter { it.key !is Predicate }.minByOrNull { it.value }?.key
//            val rightFormula = oldSequent.right.entries.filter { it.key !is Predicate }.minByOrNull { it.value }?.key
//
//            val applyLeft = when {
//                leftFormula != null && rightFormula == null -> true
//                leftFormula == null && rightFormula != null -> false
//                leftFormula != null && rightFormula != null ->
//                    oldSequent.left[leftFormula]!! < oldSequent.right[rightFormula]!!
//                else -> return false
//            }
//
//            if (applyLeft) {
//                // apply a left rule
//                when (leftFormula) {
//                    is Not -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.left.remove(leftFormula)
//                        newSequent.right[leftFormula.formula] = oldSequent.left[leftFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is And -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.left.remove(leftFormula)
//                        newSequent.left[leftFormula.formulaA] = oldSequent.left[leftFormula]!! + 1
//                        newSequent.left[leftFormula.formulaB] = oldSequent.left[leftFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is Or -> {
//                        val newSequentA = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        val newSequentB = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequentA.left.remove(leftFormula)
//                        newSequentB.left.remove(leftFormula)
//                        newSequentA.left[leftFormula.formulaA] = oldSequent.left[leftFormula]!! + 1
//                        newSequentB.left[leftFormula.formulaB] = oldSequent.left[leftFormula]!! + 1
//                        newSequentA.siblings?.add(newSequentA)
//                        newSequentB.siblings?.add(newSequentB)
//                        frontier.add(newSequentA)
//                        frontier.add(newSequentB)
//                        break
//                    }
//                    is Implies -> {
//                        val newSequentA = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        val newSequentB = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequentA.left.remove(leftFormula)
//                        newSequentB.left.remove(leftFormula)
//                        newSequentA.right[leftFormula.formulaA] = oldSequent.left[leftFormula]!! + 1
//                        newSequentB.left[leftFormula.formulaB] = oldSequent.left[leftFormula]!! + 1
//                        newSequentA.siblings?.add(newSequentA)
//                        newSequentB.siblings?.add(newSequentB)
//                        frontier.add(newSequentA)
//                        frontier.add(newSequentB)
//                        break
//                    }
//                    is ForAll -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings ?: mutableSetOf(),
//                            oldSequent.depth + 1
//                        )
//                        newSequent.left[leftFormula] = newSequent.left[leftFormula]!! + 1
//                        val formula = leftFormula.formula.replace(
//                            leftFormula.variable,
//                            UnificationTerm(oldSequent.getVariableName("t"))
//                        )
//                        formula.setInstantiationTime(oldSequent.depth + 1)
//                        if (formula !in newSequent.left) {
//                            newSequent.left[formula] = newSequent.left[leftFormula]!!
//                        }
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is ThereExists -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.left.remove(leftFormula)
//                        val variable = Variable(oldSequent.getVariableName("v"))
//                        val formula = leftFormula.formula.replace(leftFormula.variable, variable)
//                        formula.setInstantiationTime(oldSequent.depth + 1)
//                        newSequent.left[formula] = oldSequent.left[leftFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                }
//            } else {
//                // apply a right rule
//                when (rightFormula) {
//                    is Not -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.right.remove(rightFormula)
//                        newSequent.left[rightFormula.formula] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is And -> {
//                        val newSequentA = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        val newSequentB = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequentA.right.remove(rightFormula)
//                        newSequentB.right.remove(rightFormula)
//                        newSequentA.right[rightFormula.formulaA] = oldSequent.right[rightFormula]!! + 1
//                        newSequentB.right[rightFormula.formulaB] = oldSequent.right[rightFormula]!! + 1
//                        newSequentA.siblings?.add(newSequentA)
//                        newSequentB.siblings?.add(newSequentB)
//                        frontier.add(newSequentA)
//                        frontier.add(newSequentB)
//                        break
//                    }
//                    is Or -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.right.remove(rightFormula)
//                        newSequent.right[rightFormula.formulaA] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.right[rightFormula.formulaB] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is Implies -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.right.remove(rightFormula)
//                        newSequent.left[rightFormula.formulaA] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.right[rightFormula.formulaB] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is ForAll -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings,
//                            oldSequent.depth + 1
//                        )
//                        newSequent.right.remove(rightFormula)
//                        val variable = Variable(oldSequent.getVariableName("v"))
//                        val formula = rightFormula.formula.replace(rightFormula.variable, variable)
//                        formula.setInstantiationTime(oldSequent.depth + 1)
//                        newSequent.right[formula] = oldSequent.right[rightFormula]!! + 1
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                    is ThereExists -> {
//                        val newSequent = Sequent(
//                            oldSequent.left.toMutableMap(),
//                            oldSequent.right.toMutableMap(),
//                            oldSequent.siblings ?: mutableSetOf(),
//                            oldSequent.depth + 1
//                        )
//                        newSequent.right[rightFormula] = newSequent.right[rightFormula]!! + 1
//                        val formula = rightFormula.formula.replace(
//                            rightFormula.variable,
//                            UnificationTerm(oldSequent.getVariableName("t"))
//                        )
//                        formula.setInstantiationTime(oldSequent.depth + 1)
//                        if (formula !in newSequent.right) {
//                            newSequent.right[formula] = newSequent.right[rightFormula]!!
//                        }
//                        newSequent.siblings?.add(newSequent)
//                        frontier.add(newSequent)
//                        break
//                    }
//                }
//            }
//        }
//    }
//
//    // no more sequents to prove
//    return true
//}
//
//// returns true if the formula is provable
//// returns false or loops forever if the formula is not provable
//fun proveFormula(axioms: List<Formula>, formula: Formula): Boolean {
//    return proveSequent(
//        Sequent(
//            axioms.associateWith { 0 }.toMutableMap(),
//            mutableMapOf(formula to 0),
//            null,
//            0
//        )
//    )
//}
