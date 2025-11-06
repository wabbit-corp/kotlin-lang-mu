package one.wabbit.mu.types

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.typeOf
import one.wabbit.mu.types.TypeFormatter.Companion.default as F

@JvmInline
value class TypeVariable(val name: String) {
    init {
        require(name.isNotEmpty())
    }

    override fun toString(): String = name
}

@JvmInline
value class TypeComparison(val value: Int) {
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

        override fun subst(v: Subst): Constructor = Constructor(head, args.map { it.subst(v) })

        override fun toString(): String = F.format(this)
    }

    data class Forall(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }

        override fun subst(v: Subst): Forall = Forall(vars, tpe.subst(v - vars.toSet()))

        override fun toString(): String = F.format(this)
    }

    data class Exists(val vars: List<TypeVariable>, val tpe: MuType) : MuType {
        init {
            require(vars.isNotEmpty())
            require(vars.distinct().size == vars.size)
        }

        override fun subst(v: Subst): Exists = Exists(vars, tpe.subst(v - vars.toSet()))

        override fun toString(): String = F.format(this)
    }

    data class Use(val name: TypeVariable) : MuType {
        override fun subst(v: Subst): MuType {
            val r = v[name]
            if (r == null) return this
            return r.subst(v)
        }

        override fun toString(): String = F.format(this)
    }

    data class Func(
        val typeParameters: List<TypeVariable>,
        val parameters: List<MuType>,
        val returnType: MuType,
    ) : MuType {
        override fun subst(v: Subst): Func {
            val v1 = v - typeParameters.toSet()
            return Func(typeParameters, parameters.map { it.subst(v1) }, returnType.subst(v1))
        }

        override fun toString(): String = F.format(this)
    }

    data class Implicit(
        val typeParameters: List<TypeVariable>,
        val parameters: List<MuType.Constructor>,
        val returnType: MuType,
    ) : MuType {
        override fun subst(v: Subst): Implicit {
            val v1 = v - typeParameters.toSet()
            return Implicit(typeParameters, parameters.map { it.subst(v1) }, returnType.subst(v1))
        }

        override fun toString(): String = F.format(this)
    }

    abstract fun subst(v: Subst): MuType

    fun subst(v: Map<TypeVariable, MuType>): MuType = subst(Subst { v[it] })

    fun subst(v: MutableEqLattice<TypeVariable, MuType>): MuType = subst(Subst { v[it] })

    fun nullable(): MuType =
        when (this) {
            is Constructor ->
                if (head == "?") {
                    this
                } else {
                    Constructor("?", listOf(this))
                }
            is Use -> Constructor("?", listOf(this))
            is Func -> Constructor("?", listOf(this))
            is Implicit -> Constructor("?", listOf(this))
            is Forall -> Forall(vars, tpe.nullable())
            is Exists -> Exists(vars, tpe.nullable())
        }

    fun isKnownNullable(): Boolean =
        when (this) {
            is Constructor -> head == "?"
            is Forall -> tpe.isKnownNullable()
            is Exists -> tpe.isKnownNullable()
            is Use -> false
            is Func -> false
            is Implicit -> false
        }

    fun removeKnownNullability(): MuType =
        when (this) {
            is Constructor -> if (head == "?") args[0] else this
            is Forall -> Forall(vars, tpe.removeKnownNullability())
            is Exists -> Exists(vars, tpe.removeKnownNullability())
            is Use -> this
            is Func -> this
            is Implicit -> this
        }

    companion object {
        sealed interface RewrittenName {
            data class Postfix(val name: String) : RewrittenName

            data class Infix(val name: String) : RewrittenName

            data class Prefix(val name: String) : RewrittenName

            data class Name(val name: String) : RewrittenName
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

        fun Map(key: MuType, value: MuType): MuType =
            Constructor("kotlin.collections.Map", listOf(key, value))

        inline fun <reified T> lift(): MuType {
            val tpe = typeOf<T>()
            return fromKType(tpe)
        }

        class FromKTypeState(var varCount: Int) {
            // greek alphabet: αβγδεζηθικλμνξοπρστυφχψω
            fun freshVar(): TypeVariable = TypeVariable("φ${varCount++}")
        }

        private fun canonicalQualifiedNameOrThrow(tpe: KType, k: KClass<*>): String {
            // Stable, human-meaningful name if possible; otherwise an *explicit* error.
            val qn = k.qualifiedName
            if (qn == null) {
                // For local/anonymous classes, reflect a stable binary name as a fallback.
                val jn =
                    try {
                        k.java.name
                    } catch (_: Throwable) {
                        null
                    }
                if (jn != null) return jn
                // If even that fails, die loudly with context.
                throw IllegalArgumentException(
                    "fromKType: nameless classifier (local/anonymous) is unsupported for $k; KType=$tpe. " +
                        "Expose the type via a top-level or nested named class, or provide an alias."
                )
            }

            // Canonicalize JVM-isms **only when** the KType text indicates Kotlin's Nothing.
            // This avoids rewriting genuine Java `Void` in interop generics.
            if (qn == "java.lang.Void") {
                val textHead = headFromKTypeText(tpe)
                if (textHead == "kotlin.Nothing") return "kotlin.Nothing"
            }
            return qn
        }

        private fun headFromKTypeText(tpe: KType): String? {
            // tpe.toString() is stable and includes the logical head:
            // e.g., "kotlin.collections.MutableList<in kotlin.Number>", "kotlin.Function1<...>"
            val raw = tpe.toString().trim()
            if (raw.isEmpty()) return null
            val head = raw.substringBefore('<').removeSuffix("?").trim()
            return head.ifEmpty { null }
        }

        private fun canonicalHead(tpe: KType, k: KClass<*>): String {
            // Primary: KClass.qualifiedName (canon'd); Secondary: textual head if it conveys
            // mutability better.
            val base = canonicalQualifiedNameOrThrow(tpe, k)
            if (base == "kotlin.collections.List") {
                // Some projections like MutableList<in T> can appear as List in classifier;
                // prefer the textual head if it says MutableList.
                val text = headFromKTypeText(tpe)
                if (text == "kotlin.collections.MutableList") return text
            }
            return base
        }

        fun fromKType(
            tpe: KType,
            boundTypes: Set<KTypeParameter> = emptySet(),
            state: FromKTypeState = FromKTypeState(0),
        ): MuType {
            when (val classifier = tpe.classifier) {
                is KClass<*> -> {
                    val name = canonicalHead(tpe, classifier)
                    // NOTE: bounds on type parameters inside KClass<T> are not modeled here (by
                    // design).
                    val existentials = mutableListOf<TypeVariable>()

                    var result: MuType =
                        if (tpe.arguments.isNotEmpty()) {
                            Constructor(
                                name,
                                tpe.arguments.map { it ->
                                    val tpe1 = it.type
                                    if (tpe1 != null) {
                                        fromKType(tpe1, boundTypes)
                                    } else {
                                        val tv = state.freshVar()
                                        existentials.add(tv)
                                        Use(tv)
                                    }
                                },
                            )
                        } else {
                            Constructor(name, emptyList())
                        }

                    if (tpe.isMarkedNullable) {
                        result = Constructor("?", listOf(result))
                    }

                    if (existentials.isNotEmpty()) {
                        result = Exists(existentials, result)
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
                        throw IllegalStateException(
                            "fromKType: unbound type parameter '$name'. " +
                                "Known parameters: ${boundTypes.joinToString { it.name }}; KType=$tpe"
                        )
                    }
                }
                else -> {
                    val cls = classifier?.javaClass?.name ?: "null"
                    throw IllegalArgumentException(
                        "fromKType: unsupported classifier kind: $classifier (class=$cls); KType=$tpe"
                    )
                }
            }
        }
    }
}

data class Subst(val run: (TypeVariable) -> MuType?) {
    operator fun get(v: TypeVariable): MuType? = run(v)

    operator fun plus(other: Subst): Subst = Subst { v -> this[v] ?: other[v] }

    operator fun plus(other: Map<TypeVariable, MuType>): Subst = Subst { v -> this[v] ?: other[v] }

    operator fun minus(v: TypeVariable): Subst = Subst { v1 -> if (v1 == v) null else this[v1] }

    operator fun minus(vs: Set<TypeVariable>): Subst = Subst { v1 ->
        if (v1 in vs) null else this[v1]
    }
}
