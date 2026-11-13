// A solve of a constraint set without rules (facts only). A rule-less solve is a program solve: the hook must record it.
mod DlFacts {
    /// Facts: Num x3, no rule.
    pub def numbers(): Vector[Int32] =
        let p = #{
            Num(1). Num(2). Num(3).
        };
        let m = solve p;
        query m select x from Num(x)

    /// Not Datalog: a test of this function has no solve.
    pub def double(x: Int32): Int32 = x * 2
}
