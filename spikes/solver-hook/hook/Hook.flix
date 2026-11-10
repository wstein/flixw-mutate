// Plugin-supplied Flix module. Both hook routes run it:
//   route (a): compiled with the user's program, called from the rewritten bytecode of runSolverInternal;
//   route (b): its body is appended to the patched Fixpoint3/Solver.flix (see PatchSolver.java).
// `flixwBefore` runs on the constraint set that enters Fixpoint3.Solver.runSolverInternal, `flixwAfter` on
// the model that leaves it. The active mutant is read from JVM system properties (flixw.*), results are
// written to them. The module body uses fully qualified names only, so that it can be pasted into Solver.flix.
mod FlixwMutate.Hook {
    import java.lang.System
    import java.util.Objects

    type alias FwDatalog = Fixpoint3.Ast.Datalog.Datalog
    type alias FwConstraint = Fixpoint3.Ast.Datalog.Constraint

    ///
    /// Applies the active mutant (op 1 drop rule, 2 drop body atom, 3 flip polarity of a body atom).
    ///
    @DontInline
    pub def flixwBefore(d: FwDatalog): FwDatalog = match d {
        case Fixpoint3.Ast.Datalog.Datalog.Join(a, b) => Fixpoint3.Ast.Datalog.Datalog.Join(a, flixwBefore(b))
        case Fixpoint3.Ast.Datalog.Datalog.Datalog(facts, rules) =>
            if (fwIsQuery(rules)) d
            else {
                // Per test (the engine resets the counter): the ordinal of this solve call.
                let n = fwGetInt("flixw.solves");
                let t1 = fwSetProp("flixw.solves", "${n + 1}");
                let t2 = fwSetProp("flixw.solve.${n}.shape", "facts=${Vector.length(facts)} rules=${Vector.length(rules)}");
                let t3 = Vector.mapWithIndex((i, c) -> fwSetProp("flixw.solve.${n}.rule.${i}", "${c}"), rules);
                let t4 = fwSetProp("flixw.solve.${n}.kinds", Vector.join(",", Vector.map(fwKinds, rules)));
                let touched = t1 + t2 + Vector.length(t3) + t4;
                let op = fwGetInt("flixw.op");
                let r = fwGetInt("flixw.rule");
                let a = fwGetInt("flixw.atom");
                let target = Int32.fromString(unsafe IO { System.getProperty("flixw.target", "-1") }) |> Option.getWithDefault(-1);
                if (touched < 0 or op == 0 or (target >= 0 and target != n)) d
                else {
                    let mutated = if (op == 1) fwDropRule(r, rules)
                        else if (op == 2) fwOnRule(r, fwDropAtom(a), rules)
                        else fwOnRule(r, fwFlipAtom(a), rules);
                    // Fixpoint3 does not reject an unstratifiable program, so the hook does (see the README).
                    let bad = if (fwGetInt("flixw.stratcheck") == 1) fwNegativeCycle(mutated) else false;
                    let t5 = if (bad) fwSetProp("flixw.invalid", "unstratifiable") else 0;
                    if (t5 < 0) d else Fixpoint3.Ast.Datalog.Datalog.Datalog(facts, mutated)
                }
            }
        case other => other
    }

    /// One character per body predicate: `+` positive atom, `-` negated atom, `?` guard or functional.
    def fwKinds(c: FwConstraint): String = match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(_, body) =>
            body |> Vector.map(b -> match b {
                case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(_, _, Fixpoint3.Ast.Datalog.Polarity.Positive, _, _) => "+"
                case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(_, _, Fixpoint3.Ast.Datalog.Polarity.Negative, _, _) => "-"
                case _ => "?"
            }) |> Vector.join("")
    }

    /// A query lowers to a constraint set whose rule heads are `$Result`; it is not part of the user's program.
    def fwIsQuery(rules: Vector[FwConstraint]): Bool =
        Vector.exists(match Fixpoint3.Ast.Datalog.Constraint.Constraint(Fixpoint3.Ast.Datalog.HeadPredicate.HeadAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(name, _), _, _), _) -> name == "$Result", rules)

    /// Before the solver numbers them every PredSym has id 0, so the predicates are told apart by name.
    /// True if a negated body atom lies on a dependency cycle (the program is not stratifiable).
    def fwNegativeCycle(rules: Vector[FwConstraint]): Bool =
        let edges: List[(String, String, Bool)] = rules |> Vector.toList |> List.flatMap(fwEdges);
        let deps = List.foldLeft(m -> match (h, b, _) -> Map.insertWith(Set.union, h, Set#{b}, m), Map#{}, edges);
        List.exists(match (h, b, neg) -> neg and Set.memberOf(h, fwClosure(Set#{b}, Set#{b}, deps)), edges)

    def fwEdges(c: FwConstraint): List[(String, String, Bool)] = match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(Fixpoint3.Ast.Datalog.HeadPredicate.HeadAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(h, _), _, _), body) =>
            body |> Vector.toList |> List.filterMap(b -> match b {
                case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(bid, _), _, pol, _, _) =>
                    Some((h, bid, match pol {
                        case Fixpoint3.Ast.Datalog.Polarity.Negative => true
                        case _ => false
                    }))
                case _ => None
            })
    }

    /// The predicates reachable from `frontier` along "depends on" edges.
    def fwClosure(frontier: Set[String], seen: Set[String], deps: Map[String, Set[String]]): Set[String] =
        let next = frontier |> Set.toList |> List.flatMap(x -> Map.getWithDefault(x, Set#{}, deps) |> Set.toList)
                           |> List.filter(x -> not Set.memberOf(x, seen)) |> List.toSet;
        if (Set.isEmpty(next)) seen else fwClosure(next, seen ++ next, deps)

    ///
    /// Records a canonical text of the model. The relations come in `RelSym` order, the facts in key order.
    ///
    @DontInline
    pub def flixwAfter(m: FwDatalog): FwDatalog = match m {
        case Fixpoint3.Ast.Datalog.Datalog.Model(db) =>
            let n = fwGetInt("flixw.models");
            let t1 = fwSetProp("flixw.models", "${n + 1}");
            let t2 = fwSetProp("flixw.model.${n}", fwFingerprint(db));
            if (t1 + t2 < 0) fwUnreachable(m) else m
        case _ => m
    }

    /// Returns 1 if the property had a value, else 0. The result must be used by the caller: Flix may drop a
    /// pure-typed `unsafe IO` call whose result is unused.
    def fwSetProp(key: String, value: String): Int32 = unsafe IO {
        if (Objects.isNull(System.setProperty(key, value))) 0 else 1
    }

    def fwUnreachable(m: FwDatalog): FwDatalog = m

    def fwGetInt(key: String): Int32 =
        Int32.fromString(unsafe IO { System.getProperty(key, "0") }) |> Option.getWithDefault(0)

    def fwFingerprint(db: Map[Fixpoint3.Ast.Ram.RelSym, BPlusTree[Vector[Fixpoint3.Boxed], Fixpoint3.Boxed, Static]]): String = unsafe IO {
        region rc {
            let sb = StringBuilder.empty(rc);
            foreach ((relSym, tree) <- db) {
                match relSym {
                    case Fixpoint3.Ast.Ram.RelSym.Symbol(Fixpoint3.Ast.Shared.PredSym.PredSym(name, _), _, _) =>
                        foreach ((tuple, _) <- tree) {
                            StringBuilder.append("${name}(${Vector.join(", ", tuple)})\n", sb)
                        }
                }
            };
            StringBuilder.toString(sb)
        }
    }

    def fwDropRule(r: Int32, rules: Vector[FwConstraint]): Vector[FwConstraint] =
        rules |> Vector.mapWithIndex((i, c) -> (i, c)) |> Vector.filter(match (i, _) -> i != r) |> Vector.map(snd)

    def fwOnRule(r: Int32, f: FwConstraint -> FwConstraint, rules: Vector[FwConstraint]): Vector[FwConstraint] =
        Vector.mapWithIndex((i, c) -> if (i == r) f(c) else c, rules)

    def fwDropAtom(a: Int32): FwConstraint -> FwConstraint = c -> match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(h, body) =>
            Fixpoint3.Ast.Datalog.Constraint.Constraint(h, body |> Vector.mapWithIndex((i, b) -> (i, b)) |> Vector.filter(match (i, _) -> i != a) |> Vector.map(snd))
    }

    def fwFlipAtom(a: Int32): FwConstraint -> FwConstraint = c -> match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(h, body) =>
            Fixpoint3.Ast.Datalog.Constraint.Constraint(h, Vector.mapWithIndex((i, b) -> if (i == a) fwFlipBody(b) else b, body))
    }

    def fwFlipBody(b: Fixpoint3.Ast.Datalog.BodyPredicate): Fixpoint3.Ast.Datalog.BodyPredicate = match b {
        case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(p, den, Fixpoint3.Ast.Datalog.Polarity.Positive, fx, ts) =>
            Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(p, den, Fixpoint3.Ast.Datalog.Polarity.Negative, fx, ts)
        case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(p, den, Fixpoint3.Ast.Datalog.Polarity.Negative, fx, ts) =>
            Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(p, den, Fixpoint3.Ast.Datalog.Polarity.Positive, fx, ts)
        case other => other
    }

    // ---- route (a) only: everything below is dropped when the module body is appended to Solver.flix ----

    ///
    /// Keeps the two hook functions in the compiled program: tree shaking drops what no test or main reaches, and
    /// an inlined or unused pure call is removed. `@DontInline` plus a used result keeps them. Ignored by the runner.
    ///
    @Test
    def keepHook(): Unit \ Assert =
        let d = Fixpoint3.Ast.Datalog.Datalog.Datalog(Vector#{}, Vector#{});
        Assert.assertTrue(fwIsDatalog(flixwAfter(flixwBefore(d))))

    def fwIsDatalog(d: FwDatalog): Bool = match d {
        case Fixpoint3.Ast.Datalog.Datalog.Datalog(_, _) => true
        case _ => false
    }
}
