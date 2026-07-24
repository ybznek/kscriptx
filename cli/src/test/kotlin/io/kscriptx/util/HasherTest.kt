package io.kscriptx.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HasherTest {
    @Test
    fun md5IsStable() {
        assertEquals(Hasher.md5("hello"), Hasher.md5("hello"))
        assertEquals(32, Hasher.md5("hello").length)
    }

    @Test
    fun md5DiffersForDifferentInput() {
        assertNotEquals(Hasher.md5("a"), Hasher.md5("b"))
    }

    @Test
    fun md5AcceptsByteParts() {
        val a = Hasher.md5("ab".toByteArray())
        val b = Hasher.md5("a".toByteArray(), "b".toByteArray())
        assertEquals(a, b)
    }
}
