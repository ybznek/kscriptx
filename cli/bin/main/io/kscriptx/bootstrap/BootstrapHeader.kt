package io.kscriptx.bootstrap

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

object BootstrapHeader {
    private val header = """
        |#!/bin/bash
        |//usr/bin/env echo >/dev/null; if ! command -v kscriptx >/dev/null 2>&1; then
        |//usr/bin/env echo >/dev/null;   echo "kscriptx not found — install from https://github.com/kscriptx/kscriptx" >&2
        |//usr/bin/env echo >/dev/null;   exit 1
        |//usr/bin/env echo >/dev/null; fi
        |//usr/bin/env echo >/dev/null; exec kscriptx "${'$'}0" "${'$'}@"
        |
    """.trimMargin()

    fun add(scriptFile: Path) {
        val content = scriptFile.readText()
        if (content.contains("exec kscriptx")) {
            println("Bootstrap header already present: $scriptFile")
            return
        }
        val body = if (content.startsWith("#!")) {
            content.lineSequence().drop(1).joinToString("\n").trimStart('\n')
        } else content
        scriptFile.writeText(header + body)
        scriptFile.toFile().setExecutable(true)
        println("Added bootstrap header to $scriptFile")
    }
}
