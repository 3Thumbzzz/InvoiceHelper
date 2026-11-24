package com.tententen.invoicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbnTest {
    @Test
    fun blankIsFine() {
        assertNull(abnError(""))
        assertNull(abnError("   "))
    }

    @Test
    fun validAbnPassesWithOrWithoutSpaces() {
        assertNull(abnError("51 824 753 556"))
        assertNull(abnError("51824753556"))
    }

    @Test
    fun wrongLengthOrLetters() {
        val msg = "ABN must be 11 digits"
        assertEquals(msg, abnError("5182475355"))
        assertEquals(msg, abnError("518247535561"))
        assertEquals(msg, abnError("51 824 753 55a"))
    }

    @Test
    fun checksumCatchesTypos() {
        assertEquals("Not a valid ABN (check for typos)", abnError("51 824 753 557"))
    }
}
