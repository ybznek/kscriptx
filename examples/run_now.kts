#!/usr/bin/env kscriptx
@file:DependsOn("org.jsoup:jsoup:1.17.2")

import org.jsoup.Jsoup

val html = Jsoup.parse("""<html><body id="b">hello from absolute-path kscript</body></html>""")
println(html.getElementById("b")!!.text())
args.forEach { println("arg=$it") }
// x
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
println("a")
