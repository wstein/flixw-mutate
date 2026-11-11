// Datalog ground truth of the spike (#22). A rule is "fired" if it derives at least one tuple first (provenance).
mod DlNet {
    /// Rules 0 to 3 of the set below, facts: Edge x3, Start, Tag.
    pub def reachable(): Vector[Int32] =
        let p = #{
            Edge(1, 2). Edge(2, 3). Edge(7, 8).
            Start(1).
            Tag(5).
            Reach(x) :- Start(x).
            Reach(y) :- Reach(x), Edge(x, y).
            Dead(x) :- Reach(x), Edge(x, 99).
            Lone(x) :- Edge(x, _), not Reach(x).
        };
        let m = solve p;
        query m select x from Reach(x)

    /// A second constraint set, queried directly (`query p select ...`, no `solve`): rules and the query rule in one solve.
    pub def evens(): Vector[Int32] =
        let p = #{
            Num(2). Num(3). Num(4).
            Even(x) :- Num(x), if (Int32.remainder(x, 2) == 0).
        };
        query p select x from Even(x)

    /// Not Datalog: a test of this function has no solve.
    pub def double(x: Int32): Int32 = x * 2
}
