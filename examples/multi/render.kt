// Presentation helpers for examples/multi/main.kts

fun renderReport(title: String, s: Summary): String = buildString {
    appendLine("=== $title ===")
    appendLine("count : ${s.count}")
    appendLine("sum   : ${s.sum}")
    appendLine("mean  : ${"%.2f".format(s.mean)}")
    appendLine("min   : ${s.min}")
    appendLine("max   : ${s.max}")
    appendLine("median: ${"%.1f".format(s.median)}")
}
