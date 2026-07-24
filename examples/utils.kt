fun List<Int>.median(): Double {
    val sorted = sorted()
    val mid = size / 2
    return if (size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
}
