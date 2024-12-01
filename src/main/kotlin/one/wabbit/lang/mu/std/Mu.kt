package one.wabbit.lang.mu.std

import kotlinx.collections.immutable.*
import one.wabbit.lang.mu.*
import one.wabbit.levenshtein.levenshtein
import one.wabbit.math.Rational
import one.wabbit.data.Either
import one.wabbit.data.Left
import one.wabbit.data.Right
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import kotlin.reflect.*
import kotlin.reflect.jvm.isAccessible

sealed interface MuIO<R> {
    data class Scoped<R>(val nested: MuIO<R>): MuIO<R>
    data class SetLocal<R>(val name: String, val value: MuStdValue): MuIO<R>
}

object Mu {
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
    annotation class Export(val name: String = "")

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
    annotation class Doc(val name: String = "")

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
    annotation class Instance()

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Name(val name: String)

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Context()

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY)
    annotation class Const

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Optional

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class ZeroOrMore

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class OneOrMore

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.VALUE_PARAMETER)
    annotation class Quoted
}

data class MuStdModule(val definitions: ImmutableMap<String, MuStdValue>)

data class MuStdFunc(
    val name: String?,
    val typeParameters: List<TypeVariable>,
    val parameters: List<Arg<MuStdValue>>,
    val run: (MuStdContext, Map<String, MuStdValue>) -> Pair<MuStdContext, MuStdValue>,
)

internal fun makeInstanceFromMember(jvmModuleRef: Any, member: KCallable<*>): Instance<MuStdValue> {
    when (member) {
        is KProperty -> {
            val returnType = MuType.fromKType(member.returnType)
            require(member !is KMutableProperty<*>) { "Instance property must be read-only" }
            require(returnType is MuType.Constructor) { "Instance property type must be a MuType.Constructor" }
            return Instance(emptyList(), emptyList(), returnType) {
                val result = try {
                    member.call(jvmModuleRef)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
                MuStdValue.unsafeLift(result, returnType)
            }
        }

        is KFunction -> {
            val typeParameters = member.typeParameters
            val parameters = mutableListOf<MuType.Constructor>()
            for (p in member.parameters) {
                if (p.kind == KParameter.Kind.INSTANCE) continue
                val type = MuType.fromKType(p.type, typeParameters.toSet())
                require(type is MuType.Constructor) { "Instance parameter must be a MuType.Constructor" }
                parameters.add(type)
            }
            val returnType = MuType.fromKType(member.returnType, typeParameters.toSet())
            require(returnType is MuType.Constructor)
            return Instance(typeParameters.map { TypeVariable(it.name) }, parameters, returnType) { args: List<MuStdValue> ->
                val result = try {
                    member.call(jvmModuleRef, *args.map { it.unsafeValue }.toTypedArray())
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
                MuStdValue.unsafeLift(result, returnType)
            }
        }

        else -> {
            error("Unsupported instance type: ${member.javaClass}")
        }
    }
}

internal fun makeValueFromMember(jvmModuleRef: Any, exportName: String, member: KCallable<*>): MuStdValue {
    when (member) {
        is KMutableProperty -> {
            val propertyType = MuType.fromKType(member.returnType)
            val args = listOf(Arg<MuStdValue>("value", false, ArgArity.Optional, propertyType))

            member.isAccessible = true
            return MuStdValue.func(
                exportName,
                emptyList(),
                args,
                propertyType
            ) { ctx, argMap ->
                val value = argMap["value"]
                val oldValue = member.getter.call(jvmModuleRef)
                if (value == null) {
                    return@func ctx to MuStdValue.unsafeLift(oldValue, propertyType)
                }

                val state = TyperState<MuStdValue>(ctx.instances)
                try {
                    state.unify(propertyType, value.type)
                } catch (e: Throwable) {
                    val (_, r) = state.resolve(listOf(MuType.Constructor("one.wabbit.lang.mu.std.Upcast", listOf(value.type, propertyType))))

                    @Suppress("UNCHECKED_CAST")
                    val upcast = r[0].unsafeValue as Upcast<Any?, Any?>
                    member.setter.call(jvmModuleRef, upcast.upcast(value.unsafeValue))
                    return@func ctx to MuStdValue.unsafeLift(oldValue, propertyType)
                }

                member.setter.call(jvmModuleRef, value.unsafeValue)
                ctx to MuStdValue.unsafeLift(oldValue, propertyType)
            }
        }

        is KProperty -> {
            val propertyType = MuType.fromKType(member.returnType)
            val isConst = member.annotations.any { it is Mu.Const }
            if (!isConst) {
                member.isAccessible = true
                return MuStdValue.func(
                    exportName,
                    emptyList(),
                    emptyList(),
                    propertyType
                ) { ctx, argMap ->
                    val result = member.getter.call(jvmModuleRef)
                    ctx to MuStdValue.unsafeLift(result, propertyType)
                }
            } else {
                val prop = member.getter.call(jvmModuleRef)
                return MuStdValue.unsafeLift(prop, propertyType)
            }
        }

        is KFunction -> {
            val typeParameters = member.typeParameters

            var contextArg: Boolean = false
            val parameters = mutableListOf<Arg<MuStdValue>>()
            val implicits = mutableListOf<MuType.Constructor>()
            for (paramIndex in member.parameters.indices) {
                val p = member.parameters[paramIndex]
                if (p.kind == KParameter.Kind.INSTANCE) {
                    check(paramIndex == 0) { "Instance parameter must be the first parameter" }
                    continue
                }

                if (p.type.classifier == MuStdContext::class) {
                    require(paramIndex == 0 || paramIndex == 1)
                    if (paramIndex == 1) {
                        check(member.parameters[0].kind == KParameter.Kind.INSTANCE)
                    }
                    val hasContextArg = p.annotations.any { it is Mu.Context }
                    contextArg = true
                    check(hasContextArg) { "Context parameter must be annotated with @Mu.Context" }
                    continue
                }

                if (p.annotations.find { it is Mu.Instance } != null) {
                    val type = MuType.fromKType(p.type, typeParameters.toSet())
                    require(type is MuType.Constructor) { "Instance parameter must be a MuType.Constructor" }
                    implicits.add(type)
                    continue
                }

                val type = MuType.fromKType(p.type, typeParameters.toSet())

                val name = p.annotations.firstOrNull { it is Mu.Name }?.let { it as Mu.Name }?.name ?: p.name

                val isQuoted = p.annotations.any { it is Mu.Quoted }

                val arity =
                    if (p.annotations.any { it is Mu.Optional }) ArgArity.Optional
                    else if (p.annotations.any { it is Mu.ZeroOrMore }) ArgArity.ZeroOrMore
                    else if (p.annotations.any { it is Mu.OneOrMore }) ArgArity.OneOrMore
                    else ArgArity.Required

                if (arity == ArgArity.Optional) {
                    require(p.type.isMarkedNullable) { "Optional argument $name must be nullable" }
                }

                if (arity == ArgArity.ZeroOrMore || arity == ArgArity.OneOrMore) {
                    require(p.type.classifier == List::class) { "Vararg argument $name must be a List" }
                }

                parameters.add(Arg(name!!, isQuoted, arity, type))
            }

            // require(member.typeParameters.isEmpty()) { "Generic functions are not supported" }
            var resultType = MuType.fromKType(member.returnType, typeParameters.toSet())

            if (contextArg) {
                check(resultType is MuType.Constructor)
                check(resultType.head == "kotlin.Pair")
                check(resultType.args.size == 2)
                check(resultType.args[0] == MuType.Constructor("one.wabbit.lang.mu.std.MuStdContext", emptyList()))
                resultType = resultType.args[1]
            }

            member.isAccessible = true
            return MuStdValue.func(
                exportName,
                typeParameters.map { TypeVariable(it.name) },
                parameters,
                resultType
            ) { ctx, argMap ->
                val args = mutableListOf<MuStdValue?>()
                parameters.mapTo(args) { argMap[it.name] }
                val state = TyperState<MuStdValue>(ctx.instances)

                for (i in 0 until args.size) {
                    val arg = args[i]
                    if (arg == null) {
                        val param = parameters[i]
                        if (param.arity == ArgArity.Required) {
                            error("Missing required argument: ${param.name}")
                        }
                    } else {
                        try {
                            state.unify(parameters[i].type, arg.type)
                        } catch (e: Throwable) {
                            try {
                                val (_, r) = state.resolve(
                                    listOf(
                                        MuType.Constructor(
                                            "one.wabbit.lang.mu.std.Upcast",
                                            listOf(arg.type, parameters[i].type)
                                        )
                                    )
                                )

                                @Suppress("UNCHECKED_CAST")
                                val upcast = r[0].unsafeValue as Upcast<Any?, Any?>
                                args[i] = MuStdValue.unsafeLift(upcast.upcast(arg.unsafeValue), parameters[i].type)
                            } catch (e: Throwable) {
                                error("Type mismatch in argument ${parameters[i].name}: ${arg.type} vs ${parameters[i].type}")
                            }
                        }
                    }
                }

                if (contextArg) {
                    args.add(0, MuStdValue.lift(ctx))
                }

                val (statePostResolution, implicitArgs) = state.resolve(implicits.map { it.subst(state.lattice) as MuType.Constructor })
                for (a in implicitArgs) {
                    args.add(a)
                }

                val resultType1 = resultType.subst(statePostResolution.lattice)

                val result = try {
                    member.call(jvmModuleRef, *args.map { it?.unsafeValue }.toTypedArray())
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }

                if (contextArg) {
                    @Suppress("UNCHECKED_CAST")
                    val newCtx = result as Pair<MuStdContext, MuStdValue>
                    newCtx.first to newCtx.second
                } else {
                    ctx to MuStdValue.unsafeLift(result, resultType1)
                }
            }
        }

        else -> {
            error("Unsupported member type: ${member.javaClass}")
        }
    }
}

data class MuStdScope(
    val parentScope: MuStdScope?,
    val file: File?,
    val openModules: PersistentSet<String>,
    val locals: PersistentMap<String, MuStdValue>
)

fun <K, V : Any> PersistentMap<K, V>.putOrUpdate(key: K, update: (V?) -> V): PersistentMap<K, V> {
    val existing = this[key]
    return if (existing == null) {
        this.put(key, update(null))
    } else {
        this.put(key, update(existing))
    }
}

private fun Collection<String>.findSimilarNames(name: String): List<String> {
    return this.mapNotNull {
        val distance = levenshtein(it, name)
        if (distance >= 10) null
        else it to distance
    }.sortedBy { it.second }.take(5).map { it.first }
}

data class MuStdContext(
    val instances: PersistentMap<String, PersistentList<Instance<MuStdValue>>>,
    val modules: PersistentMap<String, MuStdModule>,
    val scope: MuStdScope
) {
    fun newScope(file: File? = null): MuStdContext = this.copy(scope = MuStdScope(scope, file, persistentSetOf(), persistentMapOf()))
    fun popScope(): MuStdContext = this.copy(scope = scope.parentScope ?: scope)

    fun currentFile(): File? {
        var scope = this.scope
        while (true) {
            if (scope.file != null) return scope.file
            scope = scope.parentScope ?: break
        }
        return null
    }

    fun withLocal(name: String, value: MuStdValue): MuStdContext =
        this.copy(scope = scope.copy(locals = scope.locals.put(name, value)))

    fun withLocals(locals: Map<String, MuStdValue>): MuStdContext {
        var newScope = scope
        for ((name, value) in locals) {
            newScope = newScope.copy(locals = newScope.locals.put(name, value))
        }
        return this.copy(scope = newScope)
    }

    fun withOpenModule(moduleName: String): MuStdContext =
        this.copy(scope = scope.copy(openModules = scope.openModules.add(moduleName)))

    fun withNativeModule(moduleName: String, jvmModuleRef: Any): MuStdContext {
        var definitions = persistentMapOf<String, MuStdValue>()
        var newInstances = instances

        for (member in jvmModuleRef::class.members) {
            val exportAnnotations = member.annotations.filterIsInstance<Mu.Export>()
            require(exportAnnotations.size <= 1) { "Multiple export annotations on $member" }

            val instanceAnnotations = member.annotations.filterIsInstance<Mu.Instance>()
            require(instanceAnnotations.size <= 1) { "Multiple instance annotations on $member" }

            // Can't have both an export and an instance annotation
            require(exportAnnotations.isEmpty() || instanceAnnotations.isEmpty()) { "Both export and instance annotations on $member" }

            val exportAnnotation = exportAnnotations.firstOrNull()
            val instanceAnnotation = instanceAnnotations.firstOrNull()

            if (exportAnnotation != null) {
                val exportName = exportAnnotation.name.ifEmpty { member.name }
                check(exportName != "") { "Empty export name on $member" }
                definitions = definitions.put(exportName, makeValueFromMember(jvmModuleRef, exportName, member))
            }

            if (instanceAnnotation != null) {
                val instance = makeInstanceFromMember(jvmModuleRef, member)
                newInstances = newInstances.putOrUpdate(instance.returnType.head) { it?.add(instance) ?: persistentListOf(instance) }
            }
        }

        return this.copy(
            modules = modules.put(moduleName, MuStdModule(definitions)),
            instances = newInstances)
    }

    fun resolve(name: String): Either<ResolutionError, MuStdValue> {
        val m = Regex("([a-zA-Z0-9_-]+)/(.+)").matchEntire(name)
        if (m != null) {
            val (moduleName, definitionName) = m.destructured

            val module = modules[moduleName] ?: run {
                val similarNames = modules.keys.findSimilarNames(moduleName)
                return Left(ResolutionError.UnknownModuleName(moduleName, similarNames))
            }

            val result = module.definitions[definitionName]
            if (result == null) {
                val similarNames = module.definitions.keys.findSimilarNames(definitionName)
                return Left(ResolutionError.UnboundVariable(definitionName, similarNames))
            }

            return Right(result)
        } else {
            var scope = this.scope
            val allKeys = mutableSetOf<String>()
            while (true) {
                val result = scope.locals[name]
                allKeys.addAll(scope.locals.keys)
                if (result != null) return Right(result)

                for (moduleName in scope.openModules) {
                    allKeys.addAll(modules[moduleName]?.definitions?.keys ?: emptySet())
                    val module = modules[moduleName] ?: continue
                    val result = module.definitions[name]
                    if (result != null) return Right(result)
                }

                scope = scope.parentScope ?: break
            }

            val similarNames = allKeys.findSimilarNames(name)
            return Left(ResolutionError.UnboundVariable(name, similarNames))
        }
    }

    companion object : InterpreterContext<MuStdContext, MuStdValue> {
        override fun liftInteger(context: MuStdContext, value: BigInteger): MuStdValue =
            MuStdValue.lift(MuLiteralInt(value))
        override fun liftDouble(context: MuStdContext, value: Double): MuStdValue = MuStdValue.lift(value)
        override fun liftString(context: MuStdContext, value: String): MuStdValue =
            MuStdValue.lift(MuLiteralString(value))
        override fun liftRational(context: MuStdContext, value: Rational): MuStdValue = MuStdValue.lift(value)
        override fun liftExpr(context: MuStdContext, value: MuExpr): MuStdValue = MuStdValue.lift(value)

        override fun liftList(context: MuStdContext, value: List<MuStdValue>): MuStdValue {
            if (value.isEmpty()) {
                return MuStdValue.unsafeLift(value, MuType.List(MuType.Nothing))
            }

            val type = value.first().type
            if (value.all { it.type == type }) {
                return MuStdValue.unsafeLift(value.map { it.unsafeValue }, MuType.List(type))
            }

            val state = TyperState(context.instances)
            val unificationVar = MuType.Use(state.freshVar())
            val (newState, resolved) = state.resolve(value.map {
                MuType.Constructor("one.wabbit.lang.mu.std.Upcast", listOf(it.type, unificationVar))
            })

            // println("newState: ${newState.lattice}")
            // println("resolved: $resolved")

            val newType = unificationVar.subst(newState.lattice)

            val newValues = resolved.indices.map {
                val upcast = resolved[it].unsafeValue as Upcast<Any?, Any?>
                upcast.upcast(value[it].unsafeValue)
            }

            return MuStdValue.unsafeLift(newValues, MuType.List(newType))
        }

        override fun resolve(context: MuStdContext, name: String): Either<ResolutionError, MuStdValue> =
            context.resolve(name)

//        override fun resolveSimilar(context: MuStdContext, name: String, maxSize: Int): List<String> {
//            if ("/" in name) {
//                val parts = name.split("/")
//                val moduleName = parts.first()
//                val definitionName = parts.last()
//                val module = context.modules[moduleName] ?: return emptyList()
//                val env = module.definitions.keys
//                val similarNames = env.mapNotNull {
//                    val d = levenshtein(it, definitionName)
//                    if (d >= 5) null
//                    else it to d
//                }.sortedBy { it.second }.take(5).map { "$moduleName/${it.first}" }
//                return similarNames
//            }
//
//            val env = context.locals.keys + context.openModules.flatMap { context.modules[it]?.definitions?.keys ?: emptySet() }
//            val similarNames = env.mapNotNull {
//                val d = levenshtein(it, name)
//                if (d >= 5) null
//                else it to d
//            }.sortedBy { it.second }.take(5).map { it.first }
//            return similarNames
//        }

        override fun extractFunc(value: MuStdValue): InterpreterContext.MuFunc<MuStdContext, MuStdValue>? {
            return when (val rawValue = value.unsafeValue) {
                is MuStdFunc -> InterpreterContext.MuFunc(rawValue.name, rawValue.parameters) { ctx, args -> rawValue.run(ctx, args) }
                else -> null
            }
        }

        fun empty(): MuStdContext {
            return MuStdContext(
                persistentMapOf(),
                persistentMapOf(),
                MuStdScope(null, null, persistentSetOf(), persistentMapOf())
            )
        }
    }
}
