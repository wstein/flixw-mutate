mod Text {
    pub def pad(n: Int32, s: String): String =
        if (String.length(s) >= n) s else pad(n, s + "_")

    pub def isShort(s: String): Bool = String.length(s) < 4

    pub def sameLength(a: String, b: String): Bool = String.length(a) == String.length(b)

    pub def lazyLen(s: String): Int32 =
        let l = lazy (String.length(s) + 1);
        force l

    pub def divisible(n: Int32, d: Int32): Bool = Int32.modulo(n, d) == 0
}
