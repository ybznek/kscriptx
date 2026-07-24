// Shared stats helpers for examples/multi/main.kts

data class Summary(
    val count: Int,
    val sum: Int,
    val mean: Double,
    val min: Int,
    val max: Int,
    val median: Double,
)

fun summarize(values: List<Int>): Summary {
    require(values.isNotEmpty()) { "need at least one number" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid].toDouble()
    }
    return Summary(
        count = values.size,
        sum = values.sum(),
        mean = values.average(),
        min = sorted.first(),
        max = sorted.last(),
        median = median,
    )
}
