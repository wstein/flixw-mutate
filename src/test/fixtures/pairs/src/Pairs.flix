mod Pairs {
    pub def sum(p: (Int32, Int32)): Int32 =
        let (a, b) = p;
        a + b

    pub def pick(o: Option[Int32]): Int32 = match o {
        case Some(x) => x
        case None => 0
    }
}
