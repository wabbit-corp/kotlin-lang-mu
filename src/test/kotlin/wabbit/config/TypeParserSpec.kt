//package wabbit.config
//
//import std.parsing.CharInput
//import kotlin.test.Test
//
//class TypeParserSpec {
//    val examples = listOf(
//        "color : (id : String) -> (name : String) -> (value : String) -> String",
//        "define : forall a b. (params : a) ~> (body : b) ~> ()",
//        "make-set : forall a. (values* : a) -> Set a",
//        "material : (name : String) -> Material",
//        "vanilla-block-tag : (name : String) -> Set Material",
//        "(+) : Num a => (this : a) -> a -> a",
//        "true : Bool",
//        "lambda : forall a b. (params : a) -> (body : b) -> LambdaType a b",
//        "LambdaType : forall a b. (params : a) -> (body : b) -> Type 0",
//        "Bool : Type 0",
//        "make-set : forall a. (values* : a) -> Set a",
//        "make-list : forall a. (values* : a) -> List a",
//        "pair : forall a b. (first : a) -> (second : b) -> Pair a b",
//        "fmap : (a -> b) -> f a -> f b",
//        "(+) : {Monoid a} -> (this : a) -> (b : a) -> a",
//        "python : String -> IO { x : a | Dynamic a }",
//        "Monad : (m : Type -> Type) -> { Applicative m } -> Type*",
//        "Applicative : (f : Type -> Type) -> { Functor f } -> Type*",
//        "Num : (a : Type) -> { Eq a, Ord a } -> Type*",
//        "test: (Int or String) -> String",
//        "test: (Int and String) -> String",
//        "test: (not Int) -> String",
//        "test: Int? -> String"
//    )
//
//    sealed interface Type {
//        data class ForallParam(val name: String, val lowerBound: Type, val upperBound: Type) : Type
//
//        // forall a b. T
//        // forall a <: T. T
//        // forall a >: T. T
//        data class Forall(val vars: List<ForallParam>, val tpe: Type) : Type
//        // a
//        data class Use(val name: String) : Type
//        // a -> b
//        data class Arrow(val left: Type, val right: Type) : Type
//        // a ~> b
//        data class Wiggle(val left: Type, val right: Type) : Type
//        // a => b
//        data class Implies(val left: Type, val right: Type) : Type
//        // a b c
//        data class App(val func: Type, val args: List<Type>) : Type
//        // (a, b, c)
//        data class Tuple(val items: List<Type>) : Type
//        // a : T
//        data class Named(val name: String, val type: Type) : Type
//        // {a : T, b : T}
//        data class Implicit(val items: List<Pair<String, Type>>) : Type
//        // {a : T | P}
//        data class Constraint(val name: String, val type: Type, val pred: Type) : Type
//        // A or B
//        data class Or(val left: Type, val right: Type) : Type
//        // A and B
//        data class And(val left: Type, val right: Type) : Type
//        // not A
//        data class Not(val type: Type) : Type
//        // A?
//        data class Nullable(val type: Type) : Type
//    }
//
//    fun parseDecl(input: CharInput) {
//        skipWS(input)
//        val name = parseIdentifier(input)
//        skipWS(input)
//        if (input.current != ':') {
//            throw Exception("Expected ':'")
//        }
//        input.advance()
//        skipWS(input)
//        parseType(input)
//    }
//
//    fun parseIdentifier(input: CharInput): String {
//        val sb = StringBuilder()
//        assert(input.current.isLetter())
//        sb.append(input.current)
//        input.advance()
//        while (input.current.isLetterOrDigit() || input.current == '_') {
//            sb.append(input.current)
//            input.advance()
//        }
//        return sb.toString()
//    }
//
//    fun parseType(input: CharInput) {
//        val types = mutableListOf<Type>()
//        when {
//            input.current == '(' -> {
//                input.advance()
//                val type = parseType(input)
//                if (input.current != ')') {
//                    throw Exception("Expected ')'")
//                }
//                input.advance()
//            }
//            input.current.isLetter() -> {
//                val name = parseIdentifier(input)
//                skipWS(input)
//                if (name == "forall") {
//                    parseForall(input)
//                } else {
//                    parseTypeApp(input, name)
//                }
//            }
//        }
//    }
//
//    fun skipWS(input: CharInput) {
//        while (input.current.isWhitespace()) {
//            input.advance()
//        }
//    }
//
//    @Test
//    fun `parse type`() {
//        examples.forEach {
//            val result = parseDecl(CharInput.of(it))
//            println("$it -> $result")
//        }
//    }
//}
