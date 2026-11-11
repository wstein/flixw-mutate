mod Stats {
    pub def sum(xs: List[Int32]): Int32 = List.foldLeft((a, x) -> a + x, 0, xs)

    pub def maxOf(xs: List[Int32]): Int32 =
        List.foldLeft((a, x) -> if (x > a) x else a, -1000, xs)

    pub def mean(xs: List[Int32]): Int32 = Helpers.safeDiv(sum(xs), List.length(xs))

    pub def isSorted(xs: List[Int32]): Bool = match xs {
        case a :: b :: rest => a <= b and isSorted(b :: rest)
        case _ => true
    }

    pub def countAbove(t: Int32, xs: List[Int32]): Int32 =
        List.length(List.filter(x -> x > t, xs))

    pub def spread(xs: List[Int32]): Int32 = maxOf(xs) - List.foldLeft((a, x) -> if (x < a) x else a, 1000, xs)
}
