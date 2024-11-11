package lang.rho

import one.wabbit.data.*
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.collections.LinkedHashSet
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

internal const val DEBUG = false
internal const val CHECK_INVARIANT = false

// Unicode forall symbol: ∀
// Unicode logical and: ∧

internal typealias PredicateName = String
internal typealias SubstMap = ArrMap<Rho.Arg.Var, Rho.Arg.Const>

class Rho {
    data class Fact(
        @JvmField val predicate: PredicateName,
        @JvmField val args: Arr<String>
    )
    {
        override fun toString(): String {
            val freeVarCount = args.count { it == null }
            val prefix = if (freeVarCount == 0) "" else {
                val vars = (0 until freeVarCount).map { ('A' + it).toChar() }
                "∀${vars.joinToString(", ")}. "
            }
            val args = mutableListOf<String>()
            var j = 0
            for (i in this.args.indices) {
                val arg = this.args[i]
                if (arg == null) {
                    args.add(('A' + j).toString())
                    j++
                }
                else {
                    args.add(arg)
                }
            }

            val argsStr = if (args.isEmpty()) ""
            else "(${args.joinToString(", ")})"

            return "$prefix$predicate$argsStr"
        }

        private var _hashCode: Long = 0x100000000L
        override fun hashCode(): Int {
            var h = _hashCode
            if (h == 0x100000000L) {
                val hashCode = predicate.hashCode() * 31 + args.hashCode()
                _hashCode = hashCode.toLong()
                return hashCode
            }
            return h.toInt()
        }
    }

    data class Rule(
        @JvmField val antecedents: Arr<Term>,
        @JvmField val consequents: Arr<Term>
    )
    {
        init {
            if (CHECK_INVARIANT) {
                require(consequents.isNotEmpty())
                // TODO: Come up with a way to avoid this:
                val antecedentFreeVars = antecedents.toList().flatMap { it.freeVars() }.toSet()
                val consequentFreeVars = consequents.toList().flatMap { it.freeVars() }.toSet() - antecedentFreeVars
                require(consequentFreeVars.isEmpty())
            }
        }

        override fun toString(): String {
            val antecedentFreeVars = antecedents.toList().flatMap { it.freeVars() }.toSet()
            val consequentFreeVars = consequents.toList().flatMap { it.freeVars() }.toSet() - antecedentFreeVars
            val antecedentPrefix = if (antecedentFreeVars.isEmpty()) ""
            else "∀${antecedentFreeVars.joinToString(", ")}. "
            val consequentPrefix = if (consequentFreeVars.isEmpty()) ""
            else "∀${consequentFreeVars.joinToString(", ")}. "

            val antecedents = antecedents.toList().joinToString(" ∧ ") { it.toString() }
            val consequents = consequents.toList().joinToString(" ∧ ") { it.toString() }
            return "$antecedentPrefix$antecedents :- $consequentPrefix$consequents".trim()
        }

        private var _hashCode: Long = 0x100000000L
        override fun hashCode(): Int {
            var h = _hashCode
            if (h == 0x100000000L) {
                val hashCode = antecedents.hashCode() * 31 + consequents.hashCode()
                _hashCode = hashCode.toLong()
                return hashCode
            }
            return h.toInt()
        }
    }

    data class Term(
        @JvmField val predicate: PredicateName,
        @JvmField val args: Arr<Arg>
    )
    {
        fun toFact(): Fact {
            val newArgs = args.mapOrNull { (it as? Arg.Const)?.value }
            if (newArgs == null) error("Cannot convert term to fact: $this")
            return Fact(predicate, newArgs)
        }

        fun freeVars(): Set<String> {
            val result = mutableSetOf<String>()
            for (arg in args) {
                when (arg) {
                    is Arg.Var -> result.add(arg.name)
                    is Arg.Const -> {}
                }
            }
            return result
        }

        fun unify(fact: Fact): Map<Arg.Var, Arg.Const>? {
            if (predicate != fact.predicate) return null
            if (args.size != fact.args.size) return null

            val size = args.size
            // Most unifications fail, so we optimize for that case.
            for (i in 0..size-1) {
                when (val arg = args[i]) {
                    is Arg.Var -> {}
                    is Arg.Const -> if (arg.value != fact.args[i]) return null
                }
            }

            val result = mutableMapOf<Arg.Var, Arg.Const>()
            for (i in 0..size-1) {
                val arg = args[i]
                val factArg = fact.args[i]
                when (arg) {
                    is Arg.Var -> result[arg] = Arg.Const(factArg)
                    is Arg.Const -> { } // We don't need to do anything
                }
            }
            return result
        }

        fun unify(fact: Fact, map: ArrMap<Arg.Var, Arg.Const>): ArrMap<Arg.Var, Arg.Const>? {
            if (predicate != fact.predicate) return null
            val thisArgs = args
            val size = thisArgs.size
            val factArgs = fact.args
            if (size != factArgs.size) return null

            // Most unifications fail, so we optimize for that case.
            for (i in 0..size-1) {
                when (val arg = thisArgs[i]) {
                    is Arg.Var -> {
                        val value = map[arg]
                        if (value != null && (value.value != factArgs[i])) return null
                    }
                    is Arg.Const -> if (arg.value != factArgs[i]) return null
                }
            }

            var result = map
            for (i in 0..size-1) {
                val arg = thisArgs[i]
                val factArg = factArgs[i]
                when (arg) {
                    is Arg.Var -> result = result.put(arg, Arg.Const(factArg))
                    is Arg.Const -> { }
                }
            }
            return result
        }

        fun canUnify(fact: Fact): Boolean {
            if (predicate != fact.predicate) return false
            if (args.size != fact.args.size) return false

            for (i in args.indices) {
                val arg = args[i]
                val factArg = fact.args[i]
                when (arg) {
                    is Arg.Var -> {}
                    is Arg.Const -> if (arg.value != factArg) return false
                }
            }
            return true
        }

        fun subst(subst: Map<Arg.Var, Arg.Const>): Term {
            return Term(predicate, args.map {
                when (it) {
                    is Arg.Var -> subst[it] ?: it
                    is Arg.Const -> it
                }
            })
        }

        fun subst(subst: ArrMap<Arg.Var, Arg.Const>): Term {
            val newArgs = args.map {
                when (it) {
                    is Arg.Var -> subst[it] ?: it
                    is Arg.Const -> it
                }
            }
            return Term(predicate, newArgs)
        }

        override fun toString(): String {
            val argsStr = if (args.isEmpty()) ""
            else "(${args.toList().joinToString(", ")})"
            return "$predicate$argsStr"
        }

        private var _hashCode: Long = 0x100000000L
        override fun hashCode(): Int {
            val h = _hashCode
            if (h == 0x100000000L) {
                val hashCode = predicate.hashCode() * 31 + args.hashCode()
                _hashCode = hashCode.toLong()
                return hashCode
            }
            return h.toInt()
        }
    }

    sealed interface Arg {
        data class Var(val name: String) : Arg {
            init {
                require(name.isNotEmpty())
                require(name[0].isUpperCase())
            }

            override fun toString(): String = name
        }
        data class Const(val value: String) : Arg {
            override fun toString(): String = value
        }
    }

    sealed interface Node
    class RuleNode(
        val id: Rule,
        val downstreamFacts: LinkedHashSet<FactNode> = LinkedHashSet()
    ) : Node {
        override fun toString(): String = id.toString()
    }

    class FactProof(
        val isGuaranteedAcyclic: Boolean,
        internal val rule: RuleNode,
        val usedFacts: ConsList<FactNode>
    )

    class FactNode(
        val id: Fact,
        val upstreamProofs: LinkedHashMap<RuleNode, FactProof> = LinkedHashMap(),
        val downstreamFacts: LinkedHashSet<FactNode> = LinkedHashSet()
    ) : Node {
        override fun toString(): String = id.toString()
    }

    val rules: MutableMap<Rule, RuleNode> = mutableMapOf()
    val facts: MutableMap<PredicateName, MutableMap<Fact, FactNode>> = mutableMapOf()

    private fun factNodeOf(fact: Fact): FactNode {
        val predicate = fact.predicate
        val factMap = facts.getOrPut(predicate) { mutableMapOf() }
        return factMap.getOrPut(fact) { FactNode(fact) }
    }

    private val antecedentToRule: MutableMap<PredicateName, MutableSet<Rule>> = mutableMapOf()
    private val consequentToRule: MutableMap<PredicateName, MutableSet<Rule>> = mutableMapOf()

    private fun invariant() {
        if (!CHECK_INVARIANT) return

        val seenRules: MutableMap<Rule, RuleNode> = mutableMapOf()
        val seenFacts: MutableMap<Fact, FactNode> = mutableMapOf()

        fun checkUniqueness(ruleNode: RuleNode) {
            val otherRef = seenRules.put(ruleNode.id, ruleNode)
            check(otherRef == null || otherRef === ruleNode) { "Duplicate rule" }
        }
        fun checkUniqueness(factNode: FactNode) {
            val otherRef = seenFacts.put(factNode.id, factNode)
            check(otherRef == null || otherRef === factNode) { "Duplicate fact" }
        }

        for (ruleId in rules) {
            val ruleNode = ruleId.value
            checkUniqueness(ruleNode)
            check(ruleNode.id == ruleId.key) { "RuleNode rule mismatch" }
            for (downstreamFactNode in ruleNode.downstreamFacts) {
                checkUniqueness(downstreamFactNode)
                check(downstreamFactNode.upstreamProofs.contains(ruleNode)) { "FactNode upstream does not contain RuleNode" }
                check(downstreamFactNode.id.predicate in consequentToRule) { "FactNode predicate not in consequentToRule" }
                check(downstreamFactNode.id.predicate in facts) { "FactNode predicate not in facts" }
                check(downstreamFactNode.id in facts[downstreamFactNode.id.predicate]!!) {
                    val ruleIsInUpstream = ruleNode in downstreamFactNode.upstreamProofs
                    "Rule ${ruleNode.id} downstream fact ${downstreamFactNode.id} is not contained in the known facts. Rule is in upstream: $ruleIsInUpstream"
                }
            }
        }
        for (fhead in facts.keys) {
            for (factId in facts[fhead]!!) {
                val factNode = factId.value
                check(factNode.upstreamProofs.isNotEmpty()) { "`$factId` has no proofs" }
                checkUniqueness(factNode)
                check(factNode.id == factId.key) { "FactNode fact mismatch" }
                check(factId.key.predicate == fhead) { "FactNode predicate mismatch" }
                for ((upstreamRuleNode, proof) in factNode.upstreamProofs) {
                    when (upstreamRuleNode) {
                        is RuleNode -> {
                            checkUniqueness(upstreamRuleNode)
                            check(upstreamRuleNode.downstreamFacts.contains(factNode)) { "RuleNode downstream does not contain FactNode" }
                        }
                        // is FactNode -> check(upstreamRuleNode.downstream.contains(node)) { "FactNode downstream does not contain FactNode" }
                    }
                    for (upstreamFactNode in proof.usedFacts) {
                        checkUniqueness(upstreamFactNode)
                        check(upstreamFactNode.downstreamFacts.contains(factNode)) { "FactNode downstream does not contain FactNode" }
                    }
                }

                for (downstreamFactNode in factNode.downstreamFacts) {
                    checkUniqueness(downstreamFactNode)
                    check(downstreamFactNode.upstreamProofs.any { it.value.usedFacts.contains(factNode) }) { "FactNode upstream does not contain FactNode" }
                }
            }

        }
    }

    fun add(startRule: Rho.Rule): Set<Fact> {
        invariant()

        if (startRule in rules) return emptySet()

        val ruleNode = RuleNode(startRule)
        rules[startRule] = ruleNode

        for (a in startRule.antecedents) {
            antecedentToRule.getOrPut(a.predicate) { mutableSetOf() }.add(startRule)
        }
        for (c in startRule.consequents) {
            consequentToRule.getOrPut(c.predicate) { mutableSetOf() }.add(startRule)
        }

        invariant()

        if (DEBUG) println("Adding rule `$startRule`")

        val queue = ArrayDeque<Pair<Rho.Rule, SubstMap>>()
        queue.add(startRule to arrMapOf())
        val addedFacts = processQueue(queue)

        invariant()
        return addedFacts
    }

    fun remove(rule: Rho.Rule): Set<Fact> {
        invariant()

        if (rule !in rules) return emptySet()
        val ruleNode = rules[rule]!!

        if (DEBUG) println("Removing rule `$rule`")

        fun removeProofVia(factNode: FactNode, ruleNode: RuleNode): Boolean? {
            val proof = factNode.upstreamProofs[ruleNode] ?: return null
            val usedFacts = proof.usedFacts.toMutableSet()
            for ((_, p) in factNode.upstreamProofs) {
                if (p === proof) continue
                usedFacts.removeAll(p.usedFacts)
            }
            for (usedFact in usedFacts) {
                usedFact.downstreamFacts.remove(factNode)
            }
            factNode.upstreamProofs.remove(ruleNode)
            ruleNode.downstreamFacts.remove(factNode)
            return proof.isGuaranteedAcyclic
        }

        val rerunRules = LinkedHashSet<Pair<Rho.Rule, SubstMap>>()
        val removedFacts = mutableSetOf<Fact>()
        val factChecked = mutableSetOf<FactNode>()
        val factCheckQueue = ArrayDeque<FactNode>()
        for (f in ruleNode.downstreamFacts) {
            factCheckQueue.add(f)
        }
        while (factCheckQueue.isNotEmpty()) {
            val factNode = factCheckQueue.removeFirst()
            if (factNode in factChecked) continue
            factChecked.add(factNode)

            removeProofVia(factNode, ruleNode)

            if (DEBUG) println("  Processing fact `${factNode.id}`: ${factNode.upstreamProofs.map {
                it.key.id.toString() to it.value.usedFacts.map { it.id.toString() }
            }}")

            if (factNode.upstreamProofs.any { it.value.isGuaranteedAcyclic }) continue

            // We must ensure that any upstream rule does not have this fact downstream.
            removedFacts.add(factNode.id)
            facts[factNode.id.predicate]?.remove(factNode.id)
            if (DEBUG) println("  Removed fact `${factNode.id}`")

            for ((upstreamRule, proof) in factNode.upstreamProofs) {
                upstreamRule.downstreamFacts.remove(factNode)
                for (consequent in upstreamRule.id.consequents) {
                    if (upstreamRule === ruleNode) continue
                    val subst = consequent.unify(factNode.id, arrMapOf()) ?: continue
                    if (DEBUG) println("    Marking `${upstreamRule.id}` dirty because `${factNode.id}` got removed")
                    rerunRules.add(upstreamRule.id to subst)
                }

                for (upstreamFact in proof.usedFacts) {
                    upstreamFact.downstreamFacts.remove(factNode)
                }
            }

            factNode.upstreamProofs.remove(ruleNode)

            for (downstreamFactNode in factNode.downstreamFacts.toList()) {
                var removedProof = false
                val it = downstreamFactNode.upstreamProofs.filter { it.value.usedFacts.contains(factNode) }
                for (p in it) {
                    removeProofVia(downstreamFactNode, p.key)
                    if (p.key != ruleNode) rerunRules.add(p.key.id to arrMapOf())
                    removedProof = true
                }
                if (removedProof) {
                    factChecked.remove(downstreamFactNode)
                }
                if (removedProof || downstreamFactNode !in factChecked) {
                    factCheckQueue.add(downstreamFactNode)
                    if (DEBUG) println("    Adding downstream fact `${downstreamFactNode.id}` to the queue")
                }
            }
        }

        // NOTE: this is not necessary strictly speaking
        ruleNode.downstreamFacts.clear()

        rules.remove(rule)
        for (a in rule.antecedents) {
            antecedentToRule[a.predicate]?.remove(rule)
        }
        for (c in rule.consequents) {
            consequentToRule[c.predicate]?.remove(rule)
        }

        invariant()

        if (DEBUG) {
            println("  Processing queue")
            println("  Queue size: ${rerunRules.size}")
            for (q in rerunRules) {
                println("    $q")
            }
            println("  Current facts: ${allFacts()}")
        }

//        val queue = ArrayDeque<Rule>()
//        for (r in rerunRules) queue.add(r)
        processQueue(ArrayDeque(rerunRules))

        val it = removedFacts.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val actuallyRemoved = facts.get(f.predicate)?.containsKey(f) == true
            if (!actuallyRemoved) it.remove()
        }

        invariant()

        return removedFacts
    }

    private fun processQueue(queue: ArrayDeque<Pair<Rho.Rule, SubstMap>>): Set<Fact> {
        val inQueue = HashSet<Rho.Rule>()
//        var maxQueueSize = queue.size
//        println("Initial queue size: ${queue.size}. Unique rules = ${queue.map { it.first }.toSet().size} / ${rules.size}")
        for ((rule, map) in queue)
            if(map.isEmpty()) inQueue.add(rule)

        val addedFacts: MutableSet<Fact> = mutableSetOf()

        while (queue.isNotEmpty()) {
//            maxQueueSize = maxOf(maxQueueSize, queue.size)
            val (headRule, initialSubst) = queue.removeFirst()
            if (initialSubst.isEmpty()) inQueue.remove(headRule)

            val newFacts = mutableMapOf<Fact, ConsList<FactNode>>()
            fun process(
                usedFacts: ConsList<FactNode>,
                index: Int, antecedents: Arr<Term>,
                mapping: ArrMap<Arg.Var, Arg.Const>,
                consequents: Arr<Term>
            ) {
                if (index == antecedents.size) {
                    for (consequent in consequents) {
                        val fact = consequent.subst(mapping).toFact()
                        if (usedFacts.any { it.id == fact }) continue
                        newFacts[fact] = usedFacts
                    }
                } else {
                    val antecedent = antecedents[index]
                    val facts = facts[antecedent.predicate]
                    if (facts.isNullOrEmpty()) return
                    for ((fact, factNode) in facts) {
                        val subst = antecedent.unify(fact, mapping) ?: continue
                        val newFacts = usedFacts.cons(factNode)
                        process(newFacts, index + 1, antecedents, subst, consequents)
                    }
                }
            }

            if (DEBUG) println("  Processing rule `$headRule`")
            process(emptyConsList(), 0, headRule.antecedents, initialSubst, headRule.consequents)

            for ((fact, usedFacts) in newFacts) {
                val headRuleNode = rules[headRule]!!

                val alreadyExists = facts[fact.predicate]?.containsKey(fact) ?: false
                val factNode = factNodeOf(fact)

                val alreadyProvenViaSameRule = alreadyExists && factNode.upstreamProofs.containsKey(rules[headRule]!!)
                if (alreadyProvenViaSameRule) continue
                // if (alreadyExists) continue

                factNode.upstreamProofs[headRuleNode] = FactProof(
                    isGuaranteedAcyclic = !alreadyExists || usedFacts.isEmpty(),
                    headRuleNode, usedFacts)
                headRuleNode.downstreamFacts.add(factNode)

                for (usedFactNode in usedFacts) {
                    usedFactNode.downstreamFacts.add(factNode)
                }

                if (DEBUG) println("    Adding fact `$fact` (new parents = ${factNode.upstreamProofs})")

                if (!alreadyExists) {
                    for (r in antecedentToRule[fact.predicate] ?: emptyList()) {
                        if (!r.antecedents.any { it.canUnify(fact) }) continue
                        if (!inQueue.contains(r)) queue.add(r to arrMapOf())
                        if (DEBUG) println("    Marking `$r` dirty")
                    }

                    addedFacts.add(fact)
                }
            }
        }

        // println("Max queue size: $maxQueueSize")

        return addedFacts
    }

    operator fun get(fact: Fact): Boolean {
        return facts[fact.predicate]?.containsKey(fact) ?: false
    }

    fun allFacts(): List<Fact> = facts.flatMap { (predicate, facts) ->
        facts.keys.map { Fact(predicate, it.args) }
    }
}
