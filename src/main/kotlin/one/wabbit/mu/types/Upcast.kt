// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.mu.types

import java.math.BigInteger
import one.wabbit.math.Rational

fun interface Upcast<A, B> {
    operator fun invoke(value: A): B

    companion object {
        val TypeName = Upcast::class.qualifiedName!!

        fun <A, B> of(f: (A) -> B): Upcast<A, B> =
            object : Upcast<A, B> {
                override fun invoke(value: A): B = f(value)
            }

        fun <A : B, B> id(): Upcast<A, B> = of { it }
    }
}

fun interface FromString<A> {
    fun fromString(value: String): A
}

fun interface FromInteger<A> {
    fun fromInteger(value: BigInteger): A
}

fun interface FromRational<A> {
    fun fromDouble(value: Rational): A
}

fun interface FromDouble<A> {
    fun fromDouble(value: Double): A
}

interface Eq<A> {
    fun eq(a: A, b: A): Boolean

    companion object {
        val TypeName = Eq::class.qualifiedName!!

        fun <A> of(f: (A, A) -> Boolean): Eq<A> =
            object : Eq<A> {
                override fun eq(a: A, b: A): Boolean = f(a, b)
            }

        fun <A> byEquals(): Eq<A> = of { a, b -> a == b }

        fun <A> byReference(): Eq<A> = of { a, b -> a === b }
    }
}
