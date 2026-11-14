package com.microfoods.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun allChecks() {
        val (n, failures) = Checks.runAll()
        failures.forEach { println("FAIL  ${it.name}\n      ${it.why}") }
        println("${n - failures.size}/$n checks passed")
        assertTrue("${failures.size} of $n checks failed", failures.isEmpty())
    }
}
