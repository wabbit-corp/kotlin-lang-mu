package lang.mu.std

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.typeOf

@JvmInline value class TypeVariable(val name: String) {
    init {
        require(name.isNotEmpty())
    }

    override fun toString(): String = name
}

@JvmInline value class TypeComparison(val value: Int) {
    companion object {
        val Equal = TypeComparison(1)
        val Unknown = TypeComparison(0)
        val NotEqual = TypeComparison(-1)
    }
}

sealed interface MuType {
    data class Constructor(val head: String, val args: List<MuType>) : MuType {
        init {
            if (head == "?") {
                require(args.size == 1)
                require(!args[0].isKnownNullable())
            }
        }

        override fun toString(): String {
            val head = TYPE_REWRITES[head] ?: RewrittenName.Name(head)

            when (head) {
                is RewrittenName.Name -> {
                    if (args.isEmpty()) return head.name
                    else return "${head.name}[${args.joinToString(", ")}]"
                }
                is RewrittenName.Infix -> {
                    check(args.size == 2) { "Expected 2 arguments for operator: $head" }
                    return "(${args[0]} ${head.name} ${args[1]})"
                }
                is RewrittenName.Postfix -> {
                    check(args.size == 1) { "Expected 1 argument for operator: $head" }
                    return "${args[0]}${head.name}"
                }
                is RewrittenName.Prefix -> {
                    check(args.size == 1) { "Expected 1 argument for operator: $head" }
                    return "${head.name}${args[0]}"
                }
            }
        }

        override fun subst(v: Subst): MuType.Constructor =
            Constructor(head, args.map { it.subst(v) })
    }
    data class Forall(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }
        override fun toString(): String = "forall ${vars.joinToString(", ")}. $tpe"

        override fun subst(v: Subst): MuType.Forall =
            Forall(vars, tpe.subst(v - vars.toSet()))
    }
    data class Exists(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }
        override fun toString(): String = "exists ${vars.joinToString(", ")}. $tpe"

        override fun subst(v: Subst): MuType.Exists =
            Exists(vars, tpe.subst(v - vars.toSet()))
    }

    data class Use(val name: TypeVariable) : MuType {
        override fun toString(): String = "&$name"

        override fun subst(v: Subst): MuType {
            val r = v[name]
            if (r == null) return this
            return r.subst(v)
        }
    }
    data class Func(val typeParameters: List<TypeVariable>, val parameters: List<MuType>, val returnType: MuType) : MuType {
        override fun toString(): String {
            if (typeParameters.isEmpty()) {
                return "(${parameters.joinToString(", ")}) -> $returnType"
            } else {
                return "[${typeParameters.joinToString(", ")}] (${parameters.joinToString(", ")}) -> $returnType"
            }
        }

        override fun subst(v: Subst): MuType.Func {
            val v1 = v - typeParameters.toSet()
            return Func(typeParameters, parameters.map { it.subst(v1) }, returnType.subst(v1))
        }
    }

    abstract fun subst(v: Subst): MuType
    fun subst(v: Map<TypeVariable, MuType>): MuType = subst(Subst { v[it] })
    fun subst(v: MutableEqLattice<TypeVariable, MuType>): MuType = subst(Subst { v[it] })

    fun isKnownNullable(): Boolean = when (this) {
        is Constructor -> head == "?"
        is Forall -> tpe.isKnownNullable()
        is Exists -> tpe.isKnownNullable()
        is Use -> false
        is Func -> false
    }

    fun removeKnownNullability(): MuType = when (this) {
        is Constructor -> if (head == "?") args[0] else this
        is Forall -> Forall(vars, tpe.removeKnownNullability())
        is Exists -> Exists(vars, tpe.removeKnownNullability())
        is Use -> this
        is Func -> this
    }

    companion object {
        val Nothing: MuType = Constructor("kotlin.Nothing", emptyList())
        val BigInteger: MuType = Constructor("java.math.BigInteger", emptyList())
        val String: MuType = Constructor("kotlin.String", emptyList())
        val Int: MuType = Constructor("kotlin.Int", emptyList())
        val Double: MuType = Constructor("kotlin.Double", emptyList())
        val Boolean: MuType = Constructor("kotlin.Boolean", emptyList())
        val Unit: MuType = Constructor("kotlin.Unit", emptyList())
        fun List(tpe: MuType): MuType = Constructor("kotlin.collections.List", listOf(tpe))
        fun Set(tpe: MuType): MuType = Constructor("kotlin.collections.Set", listOf(tpe))
        fun Map(key: MuType, value: MuType): MuType = Constructor("kotlin.collections.Map", listOf(key, value))

        inline fun <reified T> lift(): MuType {
            val tpe = typeOf<T>()
            return fromKType(tpe)
        }

        class FromKTypeState(var varCount: Int) {
            // greek alphabet: αβγδεζηθικλμνξοπρστυφχψω
            fun freshVar(): TypeVariable = TypeVariable("φ${varCount++}")
        }

        fun fromKType(tpe: KType, boundTypes: Set<KTypeParameter> = emptySet(), state: FromKTypeState = FromKTypeState(0)): MuType {
            when (val classifier = tpe.classifier) {
                is KClass<*> -> {
                    val name = classifier.qualifiedName!!
                    // FIXME: doesn't handle bounds
                    val existentials = mutableListOf<TypeVariable>()

                    var result: MuType = if (tpe.arguments.isNotEmpty()) {
                        Constructor(name, tpe.arguments.map { it ->
                            val tpe1 = it.type
                            if (tpe1 != null)
                                fromKType(tpe1, boundTypes)
                            else {
                                val tv = state.freshVar()
                                existentials.add(tv)
                                MuType.Use(tv)
                            }
                        })
                    } else {
                        Constructor(name, emptyList())
                    }

                    if (tpe.isMarkedNullable) {
                        result = Constructor("?", listOf(result))
                    }

                    if (existentials.isNotEmpty()) {
                        result = Forall(existentials, result)
                    }

                    return result
                }
                is KTypeParameter -> {
                    val name = classifier.name
                    if (boundTypes.contains(classifier)) {
                        if (tpe.isMarkedNullable) {
                            return Constructor("?", listOf(Use(TypeVariable(name))))
                        } else {
                            return Use(TypeVariable(name))
                        }
                    } else {
                        error("Unbound type parameter: $name, known: $boundTypes")
                    }
                }
                else -> error("Unsupported classifier: $classifier : ${classifier?.javaClass}")
            }
        }
    }
}

data class Subst(val run: (TypeVariable) -> MuType?) {
    operator fun get(v: TypeVariable): MuType? = run(v)
    operator fun plus(other: Subst): Subst = Subst { v -> this[v] ?: other[v] }
    operator fun plus(other: Map<TypeVariable, MuType>): Subst = Subst { v -> this[v] ?: other[v] }
    operator fun minus(v: TypeVariable): Subst = Subst { v1 -> if (v1 == v) null else this[v1] }
    operator fun minus(vs: Set<TypeVariable>): Subst = Subst { v1 -> if (v1 in vs) null else this[v1] }
}

sealed interface RewrittenName {
    data class Postfix(val name: String): RewrittenName
    data class Infix(val name: String): RewrittenName
    data class Prefix(val name: String): RewrittenName
    data class Name(val name: String): RewrittenName
}

val TYPE_REWRITES = mapOf(
    "lang.mu.std.MuLiteralInt" to RewrittenName.Name("LitInt"),

    // bottom unicode symbol
    "kotlin.Nothing" to RewrittenName.Name("⊥"),
    "java.lang.Void" to RewrittenName.Name("⊥"),

    // top unicode symbol
    "kotlin.Any" to RewrittenName.Name("⊤"),

    "kotlin.Int" to RewrittenName.Name("Int"),
    "kotlin.Double" to RewrittenName.Name("Double"),
    "kotlin.String" to RewrittenName.Name("String"),
    "kotlin.Boolean" to RewrittenName.Name("Boolean"),
    "kotlin.Unit" to RewrittenName.Name("Unit"),
    "java.math.BigInteger" to RewrittenName.Name("BigInteger"),
    "kotlin.collections.List" to RewrittenName.Name("List"),
    "kotlin.collections.Set" to RewrittenName.Name("Set"),
    "kotlin.collections.Map" to RewrittenName.Name("Map"),

    "lang.mu.std.Upcast" to RewrittenName.Infix("<:<"),
    "std.data.Union2" to RewrittenName.Infix("|"),
    "std.base.Rational" to RewrittenName.Name("Rational"),

    "?" to RewrittenName.Postfix("?"),
)
