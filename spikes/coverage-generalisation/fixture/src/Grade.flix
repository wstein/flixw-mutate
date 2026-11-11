// Line annotations are the ground truth of the spike: H all code on the line ran, M none ran, P part of it ran.
// A line without annotation is not executable code and must not appear in a report.
mod Grade {
    pub def classify(n: Int32): String =
        if (n < 0)                                         // H
            "negative"                                     // H
        else if (n == 0)                                   // H
            "zero"                                         // H
        else
            "positive"                                     // M

    pub def name(n: Int32): String = match n {             // H
        case 0 => "zero"                                   // H
        case 1 => "one"                                    // M
        case _ => "many"                                   // H
    }

    pub def half(o: Option[Int32]): Int32 = match o {      // H
        case Some(x) if x > 0 => x / 2                     // H
        case Some(_) => 0                                  // M
        case None => -1                                    // H
    }

    pub def total(xs: List[Int32]): Int32 =
        List.foldLeft((acc, x) -> acc + x, 0, xs)          // P

    pub def fact(n: Int32): Int32 =
        if (n <= 1) 1 else n * fact(n - 1)                 // H

    pub def stats(a: Int32, b: Int32): Int32 =
        let s = a + b;                                     // H
        let d = a - b;                                     // H
        s * d                                              // H

    pub def abs(n: Int32): Int32 = if (n < 0) -n else n    // P

    pub def unused(x: Int32): Int32 =
        x * 2                                              // M

    pub def guarded(n: Int32): Int32 =
        if (n > 100)                                       // H
            bug!("too big")                                // M
        else
            n                                              // H
}
