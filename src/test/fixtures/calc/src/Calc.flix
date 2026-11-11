// Synthetic fixture for the engine tests: functional code with planted mutation outcomes.
mod Calc {
    pub def clamp(lo: Int32, hi: Int32, x: Int32): Int32 =
        if (x < lo) lo else if (x > hi) hi else x

    pub def sumTo(n: Int32): Int32 =
        if (n <= 0) 0 else n + sumTo(n - 1)

    // Int64: a mutant that counts up instead of down must not end by wrapping around.
    pub def count(n: Int64, acc: Int64): Int64 =
        if (n <= 0i64) acc else count(n - 1i64, acc + 1i64)

    pub def isEven(n: Int32): Bool = Int32.modulo(n, 2) == 0

    pub def isOdd(n: Int32): Bool = not isEven(n)

    pub def inRange(lo: Int32, hi: Int32, x: Int32): Bool = (x >= lo) and (x <= hi)

    pub def abs2(x: Int32): Int32 = if (x < 0) -x else x

    pub def greet(name: String): String = "hi " + name

    pub def scale(a: Float64, b: Float64): Float64 = a * b

    pub def bigQuotient(a: Bool, n: Int32): Bool = a and 10 / n > 1

    // Datalog is not a site of this engine (design option 6): the guard below must stay untouched.
    pub def paths(): Vector[(Int32, Int32)] =
        let p = #{
            Edge(1, 2). Edge(2, 3).
            Path(x, y) :- Edge(x, y).
            Path(x, z) :- Path(x, y), Edge(y, z), if (x < z).
        };
        query p select (x, y) from Path(x, y)

    pub def outside(lo: Int32, hi: Int32, x: Int32): Bool = x < lo or x > hi
}

// The right operand of `and` throws when it is evaluated (System.getProperty("") throws IllegalArgumentException; the call has the IO effect, so the
// compiler keeps it and its order; a pure bug! or an unsafe block may be moved or dropped). Only a lazy right operand passes testGuarded.
mod CalcGuard {
    import java.lang.System

    pub def guarded(a: Bool, n: Int32): Bool \ IO =
        a and (if (n == 0) System.getProperty("") == "x" else true)
}
