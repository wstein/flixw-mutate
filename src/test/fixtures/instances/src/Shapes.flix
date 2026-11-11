// Synthetic fixture for the engine tests: mutation sites inside instance methods, trait default
// implementations and effect handlers, not only in top-level defs.
mod Shapes {
    pub enum Box { case Box(Int32) }

    pub trait Sized[a] {
        pub def size(x: a): Int32
        pub def isBig(x: a): Bool = Sized.size(x) > 10
        pub def twice(x: a): Int32 = Sized.size(x) + Sized.size(x)
    }

    instance Sized[Box] {
        pub def size(x: Box): Int32 = match x {
            case Box.Box(n) => if (n < 0) 0 else n * 2
        }
    }

    instance Sized[Option[a]] with Sized[a] {
        pub def size(x: Option[a]): Int32 = match x {
            case None => 0
            case Some(v) => Sized.size(v) - 1
        }
    }

    eff Counter {
        def next(): Int32
    }

    pub def counted(): Int32 =
        run { Counter.next() + Counter.next() } with handler Counter {
            def next(k) = k(1 + 2)
        }
}
