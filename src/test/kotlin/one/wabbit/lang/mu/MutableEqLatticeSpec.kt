package one.wabbit.lang.mu

import one.wabbit.lang.mu.std.MutableEqLattice
import java.lang.Math.pow
import kotlin.math.roundToInt
import kotlin.test.*

class MutableEqLatticeSpec {
    val alphabet = ('a'..'z').toList().map { it.toString() }

    fun random(): MutableEqLattice<String, Unit> {
        val map = MutableEqLattice<String, Unit>()
        for (i in 0 until pow(alphabet.size.toDouble(), 2.0/3.0).roundToInt()) {
            val a = alphabet.random()
            val b = alphabet.random()
            map.join(a, b)
        }
        return map
    }

    @Test fun properties() {
        for (iter in 0..100) {
            val map = random()
            // println(map)

            for (i in 0 until 26) {
                val a = alphabet[i]
                val a1 = String(alphabet[i].toByteArray())

                assertNotSame(a, a1)

                // Reflexivity
                assertTrue(map.compare(a, a))
                assertTrue(map.compare(a1, a1))
                assertTrue(map.compare(a, a1))
                assertTrue(map.compare(a1, a))

                for (j in 0 until 26) {
                    val b = alphabet[j]
                    val ab = map.compare(a, b)
                    val ba = map.compare(b, a)

                    // Symmetry
                    assertEquals(ab, ba)

                    for (k in 0 until 26) {
                        val c = alphabet[k]

                        val ac = map.compare(a, c)
                        val bc = map.compare(b, c)

                        // Transitivity
                        if (ab && bc) {
                            assertTrue(ac)
                        }
                    }
                }
            }

            var disjoint: Pair<String, String>? = null
            for (i in 0 until 26) {
                for (j in 0 until 26) {
                    val a = alphabet[i]
                    val b = alphabet[j]

                    if (!map.compare(a, b)) {
                        disjoint = a to b
                    }
                }
            }

            if (disjoint != null) {
                val (a, b) = disjoint
                val A = alphabet.filter { map.compare(it, a) }
                val B = alphabet.filter { map.compare(it, b) }

                // Disjoint sets
                assertTrue(a in A)
                assertTrue(b in B)
                assertTrue(A.intersect(B).isEmpty())
                for (x in A) {
                    for (y in B) {
                        assertFalse(map.compare(x, y))
                    }
                }

                map.join(a, b)
                // println(map)

                for (x in A) {
                    for (y in B) {
                        assertTrue(map.compare(x, y))
                    }
                }
            }
        }
    }

    @Test fun test() {
        val map = MutableEqLattice<String, Unit>()

        map.join("e", "f")
        map.join("a", "b")
        map.join("b", "c")
        map.join("b", "d")
        assertTrue(map.compare("a", "b"))
        assertTrue(map.compare("b", "a"))
        assertTrue(map.compare("a", "c"))
        assertTrue(map.compare("c", "a"))
        assertTrue(map.compare("a", "d"))
        assertTrue(map.compare("d", "a"))
        assertTrue(map.compare("b", "c"))
        assertTrue(map.compare("c", "b"))
        assertTrue(map.compare("b", "d"))
        assertTrue(map.compare("d", "b"))
        assertTrue(map.compare("c", "d"))
        assertTrue(map.compare("d", "c"))

        assertFalse(map.compare("a", "e"))
        assertFalse(map.compare("e", "a"))
        assertFalse(map.compare("b", "e"))
        assertFalse(map.compare("e", "b"))
        assertFalse(map.compare("c", "e"))
        assertFalse(map.compare("e", "c"))
        assertFalse(map.compare("d", "e"))
        assertFalse(map.compare("e", "d"))

        assertTrue(map.compare("e", "f"))
        assertTrue(map.compare("f", "e"))
    }
}
