// Overhead fixture: hot loops with branches (the marker cost per evaluation is the difference to the plain program).
mod Hot {
    /// 10,000,000 iterations, 1 if per iteration plus one for the accumulator: with markers 3 region evaluations per iteration.
    pub def loop(i: Int32, acc: Int32): Int32 =
        if (i == 0) acc
        else loop(i - 1, acc + (if (Int32.remainder(i, 3) == 0) 1 else 2))

    pub def bucket(n: Int32): Int32 = match Int32.remainder(n, 4) {
        case 0 => 1
        case 1 => 2
        case _ => 3
    }

    /// 5,000,000 iterations, one match with 3 arms per iteration.
    pub def loopMatch(i: Int32, acc: Int32): Int32 =
        if (i == 0) acc
        else loopMatch(i - 1, acc + bucket(i))

    /// Higher-order: a lambda body evaluated 1,000,000 times.
    pub def sumTo(n: Int32): Int32 = List.foldLeft((a, x) -> a + Int32.remainder(x, 7), 0, List.range(0, n))
}
