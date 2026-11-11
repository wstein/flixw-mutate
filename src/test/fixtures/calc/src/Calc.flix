// Synthetic fixture for the engine tests: functional code with planted mutation outcomes.
mod Calc {
    pub def clamp(lo: Int32, hi: Int32, x: Int32): Int32 =
        if (x < lo) lo else if (x > hi) hi else x

    pub def sumTo(n: Int32): Int32 =
        if (n <= 0) 0 else n + sumTo(n - 1)

    pub def count(n: Int32, acc: Int32): Int32 =
        if (n <= 0) acc else count(n - 1, acc + 1)

    pub def isEven(n: Int32): Bool = Int32.modulo(n, 2) == 0

    pub def isOdd(n: Int32): Bool = not isEven(n)

    pub def inRange(lo: Int32, hi: Int32, x: Int32): Bool = (x >= lo) and (x <= hi)

    pub def abs2(x: Int32): Int32 = if (x < 0) -x else x

    pub def greet(name: String): String = "hi " + name

    pub def scale(a: Float64, b: Float64): Float64 = a * b

    pub def bigQuotient(a: Bool, n: Int32): Bool = a and 10 / n > 1
}
