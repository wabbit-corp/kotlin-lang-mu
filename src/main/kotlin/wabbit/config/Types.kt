//package wabbit.config
//
//import kotlin.reflect.KClass
//import kotlin.reflect.KType
//import kotlin.reflect.typeOf
//
//data class MuType(val head: String, val args: List<MuType>) {
//    init {
//        if (head == "?") {
//            require(args.size == 1)
//            require(args[0].head != "?")
//        }
//    }
//
////    data class Forall(val vars: List<String>, val tpe: MuType) : MuType
////    data class Use(val name: String) : MuType
//
//    fun isNullable(): Boolean {
//        return head == "?"
//    }
//
//    fun removeNullability(): MuType {
//        if (head == "?") {
//            require(args.size == 1)
//            return args[0]
//        } else {
//            return this
//        }
//    }
//
//    override fun toString(): String {
//        if (args.isEmpty()) {
//            return head
//        } else {
//            return "($head ${args.joinToString(" ")})"
//        }
//    }
//
//    companion object {
//        val nil: MuType = MuType("Null", emptyList())
//
//        inline fun <reified T> lift(): MuType {
//            val tpe = typeOf<T>()
//            return fromKType(tpe)
//        }
//
//        fun fromKType(tpe: KType): MuType {
//            var name = tpe.classifier?.let { it as KClass<*> }?.qualifiedName!!
//
//            var result = if (tpe.arguments.isNotEmpty()) {
//                MuType(name, tpe.arguments.map { fromKType(it.type!!) })
//            } else {
//                MuType(name, emptyList())
//            }
//
//            if (tpe.isMarkedNullable) {
//                return MuType("?", listOf(result))
//            } else {
//                return result
//            }
//        }
//    }
//}
