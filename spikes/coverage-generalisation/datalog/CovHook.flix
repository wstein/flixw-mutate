// Hook of the Datalog coverage prototype (#22). Route (b) of ../../solver-hook: the body of this module is
// appended inside `mod Fixpoint3.Solver` of the patched library source (see java/.../CovPatch.java), and
// `runSolverInternal` calls `flixwCover(withProv, d, model)` around the original solver `runSolverInternal0`.
//
// Per solve of the program (queries are skipped) the hook writes JVM system properties:
//   flixw.cov.solves          number of solves since the last reset
//   flixw.cov.N.rules         count of rules; flixw.cov.N.rule.I  text of rule I
//   flixw.cov.N.facts         count of facts; flixw.cov.N.fact.J  text of fact J (a constraint without a body)
//   flixw.cov.N.derived       counts per rule, "edb,r0,r1,...": model tuples whose first derivation is that rule
//                             (edb: tuples of the facts and of an injected model), from a second solve with provenance
//   flixw.cov.N.rel           "Name=count,..." tuples per relation in the model
mod FlixwCovHook {
    import java.lang.System

    @DontInline
    pub def flixwCover(withProv: Bool, d: Fixpoint3.Ast.Datalog.Datalog, m: Fixpoint3.Ast.Datalog.Datalog): Fixpoint3.Ast.Datalog.Datalog =
        if (withProv) m else match fwInput(d) {
            case None => m
            case Some((facts, rules)) =>
                if (fwOnlyResult(rules)) m
                else {
                    let n = fwGetInt("flixw.cov.solves");
                    let t1 = fwSet("flixw.cov.solves", "${n + 1}");
                    let t2 = fwSet("flixw.cov.${n}.rules", "${Vector.length(rules)}");
                    let t3 = fwSet("flixw.cov.${n}.facts", "${Vector.length(facts)}");
                    let t4 = Vector.mapWithIndex((i, c) -> fwSet("flixw.cov.${n}.rule.${i}", fwText(c)) + fwSet("flixw.cov.${n}.sig.${i}", fwSig(c)), rules);
                    let t5 = Vector.mapWithIndex((i, c) -> fwSet("flixw.cov.${n}.fact.${i}", fwText(c)), facts);
                    let prov = runSolverInternal0(true, d);
                    let t6 = fwRecord(n, Vector.length(rules), prov);
                    if (t1 + t2 + t3 + Vector.length(t4) + Vector.length(t5) + t6 < 0) bug!("flixw-cov") else m
                }
        }

    def fwInput(d: Fixpoint3.Ast.Datalog.Datalog): Option[(Vector[Fixpoint3.Ast.Datalog.Constraint], Vector[Fixpoint3.Ast.Datalog.Constraint])] = match d {
        case Fixpoint3.Ast.Datalog.Datalog.Datalog(f, r) => Some((f, r))
        case Fixpoint3.Ast.Datalog.Datalog.Join(_, b) => fwInput(b)
        case _ => None
    }

    /// A query over a model lowers to a constraint set whose rule heads are all `$Result`: no rule of the program. A query
    /// with the constraint set inside (`query p select ...`) has the program's rules and one `$Result` rule: it is recorded.
    def fwOnlyResult(rules: Vector[Fixpoint3.Ast.Datalog.Constraint]): Bool =
        Vector.forAll(match Fixpoint3.Ast.Datalog.Constraint.Constraint(Fixpoint3.Ast.Datalog.HeadPredicate.HeadAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(name, _), _, _), _) -> name == "$Result", rules)

    def fwRecord(n: Int32, nRules: Int32, prov: Fixpoint3.Ast.Datalog.Datalog): Int32 = match prov {
        case Fixpoint3.Ast.Datalog.Datalog.Provenance(_, model) => unsafe IO {
            region rc {
                let counts = Array.repeat(rc, nRules + 1, 0);
                let sb = StringBuilder.empty(rc);
                foreach ((relSym, tree) <- model) {
                    let size = Ref.fresh(rc, 0);
                    foreach ((_, (_, rule)) <- tree) {
                        Ref.put(Ref.get(size) + 1, size);
                        let i = rule + 1;
                        if (i >= 0 and i <= nRules) Array.put(Array.get(i, counts) + 1, i, counts) else ()
                    };
                    match relSym {
                        case Fixpoint3.Ast.Ram.RelSym.Symbol(Fixpoint3.Ast.Shared.PredSym.PredSym(name, _), _, _) =>
                            StringBuilder.append("${name}=${Ref.get(size)},", sb)
                    }
                };
                let _ = System.setProperty("flixw.cov.${n}.derived", Array.toList(counts) |> List.join(","));
                let _ = System.setProperty("flixw.cov.${n}.rel", StringBuilder.toString(sb));
                0
            }
        }
        case _ => 1
    }

    /// The constraint as text; a `Boxed` literal is printed with its own `ToString` (`Debug.stringify` and the constraint's `ToString` show an object id).
    def fwText(c: Fixpoint3.Ast.Datalog.Constraint): String = match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(h, body) =>
            if (Vector.length(body) == 0) "${fwHead(h)}." else "${fwHead(h)} :- ${body |> Vector.map(fwBody) |> Vector.join(", ")}."
    }

    def fwHead(h: Fixpoint3.Ast.Datalog.HeadPredicate): String = match h {
        case Fixpoint3.Ast.Datalog.HeadPredicate.HeadAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(name, _), _, terms) =>
            "${name}(${terms |> Vector.map(fwTerm) |> Vector.join(", ")})"
    }

    def fwTerm(t: Fixpoint3.Ast.Datalog.HeadTerm): String = match t {
        case Fixpoint3.Ast.Datalog.HeadTerm.Lit(v) => "${v}"
        case Fixpoint3.Ast.Datalog.HeadTerm.Var(v) => "${v}"
        case _ => "<function>"
    }

    def fwBody(b: Fixpoint3.Ast.Datalog.BodyPredicate): String = match b {
        case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(n, _), _, pol, _, terms) =>
            let neg = match pol {
                case Fixpoint3.Ast.Datalog.Polarity.Negative => "not "
                case _ => ""
            };
            "${neg}${n}(${terms |> Vector.map(fwBodyTerm) |> Vector.join(", ")})"
        case _ => "<guard or functional>"
    }

    def fwBodyTerm(t: Fixpoint3.Ast.Datalog.BodyTerm): String = match t {
        case Fixpoint3.Ast.Datalog.BodyTerm.Wild => "_"
        case Fixpoint3.Ast.Datalog.BodyTerm.Var(v) => "${v}"
        case Fixpoint3.Ast.Datalog.BodyTerm.Lit(v) => "${v}"
    }

    /// `Head<-Body1,Body2`: predicate names of the head and of the body atoms (guards and functionals left out).
    def fwSig(c: Fixpoint3.Ast.Datalog.Constraint): String = match c {
        case Fixpoint3.Ast.Datalog.Constraint.Constraint(Fixpoint3.Ast.Datalog.HeadPredicate.HeadAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(h, _), _, _), body) =>
            "${h}<-${body |> Vector.filterMap(fwBodyName) |> Vector.join(",")}"
    }

    def fwBodyName(b: Fixpoint3.Ast.Datalog.BodyPredicate): Option[String] = match b {
        case Fixpoint3.Ast.Datalog.BodyPredicate.BodyAtom(Fixpoint3.Ast.Shared.PredSym.PredSym(n, _), _, _, _, _) => Some(n)
        case _ => None
    }

    def fwSet(key: String, value: String): Int32 = unsafe IO {
        let _ = System.setProperty(key, value);
        0
    }

    def fwGetInt(key: String): Int32 =
        Int32.fromString(unsafe IO { System.getProperty(key, "0") }) |> Option.getWithDefault(0)
}
