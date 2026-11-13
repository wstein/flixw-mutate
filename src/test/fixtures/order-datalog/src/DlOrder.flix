// Fixture for the test-order tests with Datalog hits on: a constraint set (one test solves it) and a def that
// a slow test calls without checking it and a fast test checks.
mod DlOrder {
    pub def reachable(): Vector[Int32] =
        let p = #{
            Edge(1, 2). Edge(2, 3).
            Start(1).
            Reach(x) :- Start(x).
            Reach(y) :- Reach(x), Edge(x, y).
        };
        let m = solve p;
        query m select x from Reach(x)

    pub def double(x: Int32): Int32 = x * 2
}
