// Synthetic Datalog fixture for the solver-hook spike (flixw-mutate#1).
//
// One constraint set, solved once per call. The rules are numbered from 0 in the order the mutant list
// (../mutants.tsv) refers to; the hook sees them as the second vector of `Datalog(facts, rules)`
// (`Start(1).` is a fact and not in that vector). Atoms are numbered from 0 within a rule.
//
//   Reach   = nodes reachable from node 1        {1, 2, 3, 4}
//   Unreach = nodes that are not reachable       {5, 6}
//   Pair    = edges inside the reachable part    {(1,2), (2,3), (3,4)}
//   Seen    = copy of Reach that no test reads
//
// Rule 3 repeats rule 1 with a redundant `Node(y)` atom: a mutant that removes rule 1, rule 3 or the
// atom `Node(y)` leaves the model unchanged (equivalent mutant). Edge(5, 3) lets `Reach(x)` in rule 4
// matter, while `Reach(y)` in rule 4 is redundant (equivalent mutant). Dropping rule 5 changes the
// model but no test fails (survived).
mod ReachGraph {
    pub def analyse(): (Vector[Int32], Vector[Int32], Vector[(Int32, Int32)]) =
        let db = inject (1 :: 2 :: 3 :: 4 :: 5 :: 6 :: Nil) into Node/1;
        let es = inject ((1, 2) :: (2, 3) :: (3, 4) :: (5, 6) :: (5, 3) :: Nil) into Edge/2;
        let pr = #{
            Start(1).
            Reach(x) :- Start(x).
            Reach(y) :- Reach(x), Edge(x, y).
            Unreach(n) :- Node(n), not Reach(n).
            Reach(y) :- Reach(x), Edge(x, y), Node(y).
            Pair(x, y) :- Edge(x, y), Reach(x), Reach(y).
            Seen(x) :- Reach(x).
        };
        let m = solve db, es, pr;
        let r = query m select x from Reach(x);
        let u = query m select n from Unreach(n);
        let p = query m select (x, y) from Pair(x, y);
        (r, u, p)
}
