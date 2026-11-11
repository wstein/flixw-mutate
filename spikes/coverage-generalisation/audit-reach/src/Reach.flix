// Fixture of the marker audit (flixw-mutate#29): three live functions whose else branch is folded away and whose own
// class is missing or named oddly. All three rows must be LIVE:
//   Geo.area     an instance def inside a module, called through the trait signature (its class name carries a numeric suffix)
//   Nested.inner  dropped (inlined), applied only by the live Nested.outer
//   Direct.only  dropped (inlined), applied only by a test
mod Geo {
    pub trait Area[a] {
        pub def area(x: a): Int32
    }

    instance Area[Int32] {
        pub def area(x: Int32): Int32 =
            if (true) x + 1 else x + 2
    }
}

mod Nested {
    @Inline
    def inner(n: Int32): Int32 =
        if (true) n + 1 else n - 1

    pub def outer(n: Int32): Int32 = inner(n)
}

mod Direct {
    @Inline
    pub def only(n: Int32): Int32 =
        if (true) n * 2 else n * 3
}
