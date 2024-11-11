//package lang.mu
//
//import std.parsing.CharInput
//import std.parsing.Span3
//import java.util.*
//import java.util.stream.Stream
//import kotlin.test.Test
//
//data class Rule(val head: Term.Compound, val body: List<Term.Compound>) {
//    override fun toString(): String = "$head :- ${body.joinToString(", ")}"
//}
//
//data class SearchState(val goals: List<Term.Compound>)
//
///** A /term/ is either a variable or a compound.
// * atoms are just compounds with no arguments.
// */
//sealed interface Term {
//    fun substitute(bindings: Map<Var, Term>): Term = when (this) {
//        is Var -> bindings[this] ?: this
//        is Compound -> Compound(functor, args.map { it.substitute(bindings) })
//    }
//
//    fun isGrounded(): Boolean = when (this) {
//        is Var -> false
//        is Compound -> args.all { it.isGrounded() }
//    }
//    fun freeVars(): Set<Var> = when (this) {
//        is Var -> setOf(this)
//        is Compound -> args.flatMap { it.freeVars() }.toSet()
//    }
//
//    /** trying unifying with another term */
//    fun match(other: Term): Map<Var, Term>?
//
//    data class Var(val name: String) : Term {
//        init {
//            require(name.isNotEmpty())
//            require(name[0].isUpperCase())
//            require(name.all { it.isLetterOrDigit() || it == '_' })
//        }
//
//        /**
//         * Since we are a var, we unify by just mapping from ourselves
//         * to the other.
//         */
//        override fun match(other: Term): Map<Var, Term> {
//            val bindings = mutableMapOf<Var, Term>()
//            if (this !== other) {
//                bindings[this] = other
//            }
//            return bindings
//        }
//    }
//
//    data class Compound(val functor: String, val args: List<Term>) : Term {
//        init {
//            require(functor.isNotEmpty())
//            require(functor[0].isLetter())
//            require(functor.all { it.isLetterOrDigit() || it == '_' })
//        }
//
//        override fun toString(): String {
//            if (args.isEmpty()) return functor
//            return "$functor(${args.joinToString(", ")})"
//        }
//
//        /** match compounds against each other.
//         * empty() if can't match.
//         */
//        override fun match(other: Term): Map<Var, Term>? {
//            // could we assign other to this? if so, is compound
//            if (other !is Compound) return other.match(this)
//            if (other.functor != functor) return null
//            if (other.args.size != args.size) return null
//
//            // if args are same length, unify each pair of args, and
//            // merge all the bindings.
//            var finalBinding = mapOf<Var, Term>()
//            for (i in args.indices) {
//                val thisArg = args[i]
//                val otherArg: Term = other.args[i]
//                val argBinding: Map<Var, Term> = thisArg.match(otherArg) ?: return null
//                finalBinding = mergeBindings(finalBinding, argBinding) ?: return null
//            }
//            return finalBinding
//        }
//
//        /**
//         * Attempt to satisfy against a database.
//         * (normally because this is the body of a rule)
//         */
//        override fun query(database: Queryable): Stream<Term> {
//            if (functor == ",") {
//                // we build up answers to queries which can
//                // be got once all args have been matched.
//                // if an arg _isn't_ matched, we abandon that line of reasoning.
//
//                // "branch" - try and get all ways of satisfying all args,
//                // by calling solutionsForArg, starting w/ arg 0.
//
//                val emptyBindings: Map<Var, Term> = HashMap()
//                val results = solutionsForArg(args, database, 0, emptyBindings)
//                return results
//            }
//
//            return database.query(this)
//        }
//    }
//}
//
//data class Bindings(val bindings: Map<Term.Var, Term>) {
//    fun substitute(term: Term): Term {
//        return term.substitute(bindings)
//    }
//}
//
///** Merge 2 sets of bindings. It's okay if one or the other is null (that just
// * results in an empty result).
// * If they can be merged without conflict, returns an Optional of the merge,
// * else empty.
// *
// * @return Merged bindings, or empty.
// */
//fun mergeBindings(bindings1: Map<Var, Term>, bindings2: Map<Var, Term>): Map<Var, Term>? {
//    var conflict = false
//    val resultBinding: MutableMap<Var, Term> = HashMap()
//
//    resultBinding.putAll(bindings1)
//
//    for ((b2_var, b2_val) in bindings2) {
//        val b1_val = resultBinding[b2_var]
//
//        // is a var in both bindings1 and bindings2?
//        // if so, try match
//        if (b1_val != null) {
//            // try get substitution.
//            // if can't, we are in conflict.
//            // if we can, put results of substitution in our result.
//
//            val substitution: Map<Var, Term>? = b1_val.match(b2_val)
//            if (substitution == null) {
//                conflict = true
//            } else {
//                resultBinding.putAll(substitution)
//            }
//        } else {
//            // var not in both, so add b2.
//            resultBinding[b2_var] = b2_val
//        }
//    }
//
//    if (conflict) {
//        return null
//    }
//    return resultBinding
//}
//
///** things that can be queried to see if a
// * goal is satisfiable.
// */
//interface Queryable {
//    fun query(q: Queryable): Stream<Term>
//}
//
///**
// * recursive func. Get query solutions for the "current arg", and call
// * recursively to get solutions for remaining args.
// * if we've reached the end, and we're still here, we're on a line
// * of inquiry where all args were matched okay, so we return a result.
// *
// * but if an arg match fails, throw null into the stream and abort this
// * line of inquiry.
// */
//private fun solutionsForArg(args: List<Term>, database: Queryable, argIdx: Int, bindings: Map<Var, Term>): Stream<Term> {
//    if (argIdx >= args.size) { // success, we satisfied all args & have bindings.
//        // so, substitute bindings into this. (one-el array so we can return as stream).
//        val result = args.map { it.substitute(bindings) }
//        return result.stream()
//    }
//
//    // still trying to satisfy all args.
//    val currentArg: Term = args[argIdx]
//    // get results of querying whether satisfiable
//    val queryResults = database.query(currentArg.substitute(bindings) as Queryable)
//
//    // for each of those results, we should try and merge in our bindings,
//    // and call results for the next arg.
//    // So each result becomes a stream of results, that we merge.
//    val flattenedResults = queryResults.flatMap { queryResult: Term ->
//        // see if result can be merged with the current arg, then our bindings
//        // so far.
//        val bindings1 = currentArg.match(queryResult)
//            ?: return@flatMap null // if can't unify, throw null into the stream
//        val unified: Map<Var, Term>? = mergeBindings(
//            bindings1,
//            bindings
//        )
//
//        if (unified == null) {
//            return@flatMap null // if can't unify, throw null into the stream
//        }
//        solutionsForArg(args, database, argIdx + 1, unified)
//    }
//
//    return flattenedResults
//}
//
///** Database contains a list of rules, and can be queried.
// */
//data class Database(val rules: List<Rule>) : Queryable {
//    /** given a goal, query the database to see if it can be satisfied.  */
//    override fun query(goal: Queryable): Stream<Term> {
//        val db = this
//
//        // else we make a stream by mapping over rules, and expanding
//        // each rule into a potential stream of query results.
//        val ruleStrm = db.rules.stream()
//
//        // given the stream of _rules_, do a flatMap which "flattens" each
//        // rule into a stream of _terms_ (being results from matching the head
//        // of that query).
//        val termStrm = ruleStrm.flatMap<Term> { rule: Rule ->
//            val goalAsTerm = goal as Term
//            val headMatch = rule.head.match(goalAsTerm) ?: return@flatMap null
//
//            // else there were matches. So make the substitutions in head and body.
//            val head: Term = rule.head.substitute(headMatch)
//            val body: Term = rule.body.substitute(headMatch)
//            val bodyAsQ = body as Queryable // body _shouldn't_ legally ever be a var,
//
//            // but should probably enforce this.
//            val queryResults = bodyAsQ.query(db)
//
//            // do the substitutions there as well, using /map/.
//            queryResults.map<Term> { item: Term ->
//                val unifiedBits = body.match(item)
//                if (unifiedBits != null) {
//                    val x = head.substitute(unifiedBits)
//                    return@map x
//                } else {
//                    return@map null
//                }
//            } as Stream<out Term>
//        }
//
//        return termStrm
//    }
//
//    // get all query answers, and dump in a list
//    fun queryAll(goal: Queryable): List<Term> {
//        val queryResults: MutableList<Term> = ArrayList()
//        query(goal).forEach { x: Term ->
//            queryResults.add(x)
//        }
//        return queryResults
//    }
//}
//
//
//class TestSpec {
//    @Test
//    fun test() {
//        fun p(s: String) = PrologParser.parse(s)
//        val rule = p("studies(charlie, csc135).")
//        val db = Database(listOf(
//            p("studies(charlie, csc135)."),
//            p("studies(olivia, csc135)."),
//            p("studies(jack, csc131)."),
//            p("studies(arthur, csc134)."),
//            p("teaches(kirke, csc135)."),
//            p("teaches(collins, csc131)."),
//            p("teaches(collins, csc171)."),
//            p("teaches(juniper, csc134)."),
//            p("professor(X, Y) :- teaches(X, C), studies(Y, C).")
//        ))
//
//        val results = db.queryAll(p("true.").head)
//        println(results)
//    }
//}
