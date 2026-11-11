// Fixture of the marker audit (flixw-mutate#29): Fold.lit is called by a test, so it is live. Its else branch is removed
// by the compiler (constant condition), so the marker of that branch is missing from the bytecode of a live function.
mod Fold {
    pub def lit(n: Int32): Int32 =
        if (true)
            n * 2
        else
            n * 3

    pub def keep(n: Int32): Int32 =
        if (n > 0) n else 0 - n
}
