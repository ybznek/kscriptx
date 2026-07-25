#!/usr/bin/env kscriptx
// Bench: larger source → more K2 work on cold compile (still exits immediately).
fun fib(n: Int): Long {
    if (n < 2) return n.toLong()
    var a = 0L
    var b = 1L
    repeat(n - 1) {
        val c = a + b
        a = b
        b = c
    }
    return b
}

fun isPrime(n: Int): Boolean {
    if (n < 2) return false
    if (n % 2 == 0) return n == 2
    var d = 3
    while (d.toLong() * d <= n) {
        if (n % d == 0) return false
        d += 2
    }
    return true
}

fun sieve(limit: Int): Int {
    val mark = BooleanArray(limit + 1)
    var count = 0
    for (i in 2..limit) {
        if (!mark[i]) {
            count++
            var j = i * 2
            while (j <= limit) {
                mark[j] = true
                j += i
            }
        }
    }
    return count
}

data class Stats(val n: Int, val mean: Double, val max: Int)

fun summarize(xs: List<Int>): Stats {
    require(xs.isNotEmpty())
    return Stats(xs.size, xs.average(), xs.max())
}

fun render(title: String, s: Stats): String =
    "$title: n=${s.n} mean=${"%.2f".format(s.mean)} max=${s.max}"

fun hashMix(seed: Long, rounds: Int): Long {
    var x = seed
    repeat(rounds) {
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        x += 0x9E3779B97F4A7C15UL.toLong()
    }
    return x
}

fun padLeft(s: String, width: Int, ch: Char = ' '): String =
    if (s.length >= width) s else ch.toString().repeat(width - s.length) + s

fun chunkedJoin(xs: List<String>, size: Int): String =
    xs.chunked(size).joinToString(" | ") { it.joinToString(",") }

val nums = (1..40).toList()
val primes = nums.filter { isPrime(it) }
val stats = summarize(primes)
val f = fib(40)
val mixed = hashMix(f, 64)
val sieved = sieve(5_000)
println(render("large-source", stats))
println("fib40=$f mix=$mixed primes=${chunkedJoin(primes.map { padLeft(it.toString(), 2) }, 8)} sieve5k=$sieved")
