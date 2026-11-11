// Known limits of the line model, listed in expected-limits.tsv (truth: what ran; tool: what the report says).
// The annotations here are the truth; run.sh does not compare this file with them, only with expected-limits.tsv.
mod Limits {
    pub def check(x: Int32): Bool = x > 10

    pub def both(a: Bool, x: Int32): Bool = a and check(x)         // P: check(x) is not evaluated when a is false

    pub def eachSum(xs: List[Int32]): Int32 = region rc {          // H
        let r = Ref.fresh(rc, 0);                                  // H
        foreach (x <- xs) Ref.put(Ref.get(r) + x, r);              // P: the body does not run for Nil
        Ref.get(r)                                                 // H
    }
}
