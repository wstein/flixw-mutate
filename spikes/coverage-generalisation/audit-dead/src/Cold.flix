// Fixture of the marker audit (flixw-mutate#29): no test reaches Cold. The compiler drops Cold.top and then Cold.helper,
// which only top calls; Cold.knobs has a body that is a list of lambdas, so its entry gets no region (an expression
// span with unbalanced brackets) and only the evidence of the bytecode and the call graph shows that it is dead.
mod Cold {
    def helper(n: Int32): Int32 =
        if (n > 0) n else 0 - n

    pub def top(xs: List[Int32]): Int32 =
        List.sum(List.map(x -> helper(x), xs))

    // Two dropped defs that call each other: dead although each has a caller.
    def ping(n: Int32): Int32 = if (n > 0) pong(n - 1) else 0

    def pong(n: Int32): Int32 = if (n > 0) ping(n - 1) else 1

    pub def start(n: Int32): Int32 = ping(n)

    def scale(n: Int32): Int32 = n * 3

    pub def knobs(): List[List[Int32 -> Int32]] =
        List.map(by -> ((t: Int32) ->
            let s = scale(by);
            s + t), 1 :: 2 :: Nil) ::
        List.map(by -> ((t: Int32) ->
            let s = scale(by);
            s - t), 3 :: Nil) :: Nil
}
