package one.wabbit.mu.types

interface Upcast<A, B> {
    fun upcast(value: A): B

    companion object {
        val TypeName = Upcast::class.qualifiedName!!

        fun <A, B> of(f: (A) -> B): Upcast<A, B> = object : Upcast<A, B> {
            override fun upcast(value: A): B = f(value)
        }
    }
}

interface Eq<A> {
    fun eq(a: A, b: A): Boolean

    companion object {
        val TypeName = Eq::class.qualifiedName!!

        fun <A> of(f: (A, A) -> Boolean): Eq<A> = object : Eq<A> {
            override fun eq(a: A, b: A): Boolean = f(a, b)
        }
        fun <A> byEquals(): Eq<A> = of { a, b -> a == b }
        fun <A> byReference(): Eq<A> = of { a, b -> a === b }
    }
}
