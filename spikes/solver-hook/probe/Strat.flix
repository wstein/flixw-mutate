// Probe: the Flix compiler rejects a non-stratifiable constraint set by its type, also when the two halves
// come from different functions. Expected: a compile error "The expression is not stratified".
mod Strat {
    def mk(b: Bool): #{A(Int32), B(Int32), C(Int32) | r} =
        if (b) #{ A(x) :- B(x), not C(x). } else #{ C(x) :- A(x). }

    def go(): Vector[Int32] =
        let m = solve mk(true) <+> mk(false);
        query m select x from A(x)
}
