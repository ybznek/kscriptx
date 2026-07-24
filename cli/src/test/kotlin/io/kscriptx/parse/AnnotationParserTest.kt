package io.kscriptx.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationParserTest {
    @Test
    fun parsesDependsOnAndRepository() {
        val src = """
            #!/usr/bin/env kscriptx
            @file:DependsOn("org.jsoup:jsoup:1.17.2", "log4j:log4j:1.2.14")
            @file:Repository("https://repo.example.com/maven")
            @file:KotlinOptions("-J-Xmx512m")
            @file:Import("utils.kt")

            println("hi")
        """.trimIndent()

        val (config, body) = AnnotationParser.parse(src)
        assertEquals(listOf("org.jsoup:jsoup:1.17.2", "log4j:log4j:1.2.14"), config.dependencies)
        assertEquals(1, config.repositories.size)
        assertEquals("https://repo.example.com/maven", config.repositories[0].url)
        assertEquals(listOf("-J-Xmx512m"), config.kotlinOptions)
        assertEquals(listOf("utils.kt"), config.imports)
        assertTrue(body.contains("println"))
        assertTrue(!body.contains("@file:DependsOn"))
    }

    @Test
    fun parsesEntryPoint() {
        val src = """
            @file:EntryPoint("examples.Bar")
            package examples
            class Bar
        """.trimIndent()
        val (config, _) = AnnotationParser.parse(src)
        assertEquals("examples.Bar", config.entryPoint)
    }
}
