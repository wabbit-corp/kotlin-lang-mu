package one.wabbit.mu.types

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

        override fun subst(v: Subst): Constructor =
            Constructor(head, args.map { it.subst(v) })
    }
    data class Forall(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }

        override fun subst(v: Subst): Forall =
            Forall(vars, tpe.subst(v - vars.toSet()))
    }
    data class Exists(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }

        override fun subst(v: Subst): Exists =
            Exists(vars, tpe.subst(v - vars.toSet()))
    }

    data class Use(val name: TypeVariable) : MuType {
        override fun subst(v: Subst): MuType {
            val r = v[name]
            if (r == null) return this
            return r.subst(v)
        }
    }
    data class Func(val typeParameters: List<TypeVariable>, val parameters: List<MuType>, val returnType: MuType) : MuType {
        override fun subst(v: Subst): Func {
            val v1 = v - typeParameters.toSet()
            return Func(typeParameters, parameters.map { it.subst(v1) }, returnType.subst(v1))
        }
    }

    abstract fun subst(v: Subst): MuType
    fun subst(v: Map<TypeVariable, MuType>): MuType = subst(Subst { v[it] })
    fun subst(v: MutableEqLattice<TypeVariable, MuType>): MuType = subst(Subst { v[it] })

    fun nullable(): MuType = when (this) {
        is Constructor ->
            if (head == "?") this
            else Constructor("?", listOf(this))
        is Use -> Constructor("?", listOf(this))
        is Func -> Constructor("?", listOf(this))
        is Forall -> Forall(vars, tpe.nullable())
        is Exists -> Exists(vars, tpe.nullable())
    }

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
        sealed interface RewrittenName {
            data class Postfix(val name: String): RewrittenName
            data class Infix(val name: String): RewrittenName
            data class Prefix(val name: String): RewrittenName
            data class Name(val name: String): RewrittenName
        }

        val Nothing: MuType = Constructor("kotlin.Nothing", emptyList())
        val BigInteger: MuType = Constructor("java.math.BigInteger", emptyList())
        val String: MuType = Constructor("kotlin.String", emptyList())
        val Int: MuType = Constructor("kotlin.Int", emptyList())
        val Double: MuType = Constructor("kotlin.Double", emptyList())
        val Boolean: MuType = Constructor("kotlin.Boolean", emptyList())
        val Unit: MuType = Constructor("kotlin.Unit", emptyList())
        val Rational: MuType = Constructor("one.wabbit.math.Rational", emptyList())
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
                                Use(tv)
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
