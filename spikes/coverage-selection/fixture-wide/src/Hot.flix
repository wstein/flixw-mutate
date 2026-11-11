// A loop with three sites for the cost of the marker. Int64: a mutant that counts up never ends (timeout, no wrap-around).
mod Hot {
    pub def loop(n: Int64, acc: Int64): Int64 =
        if (n <= 0i64) acc else loop(n - 1i64, if (n > 5i64 and acc < 1000000000i64) acc + n else acc)
}
