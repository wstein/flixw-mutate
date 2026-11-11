mod Shapes {
    pub def area(w: Int32, h: Int32): Int32 = w * h

    pub def perimeter(w: Int32, h: Int32): Int32 = 2 * (w + h)

    pub def isSquare(w: Int32, h: Int32): Bool = w == h

    pub def fits(w: Int32, h: Int32, box: Int32): Bool = w <= box and h <= box

    pub def ratio(w: Int32, h: Int32): Int32 = Helpers.safeDiv(w * 100, h)

    pub def grow(k: Int64, w: Int32): Int32 = if (k <= 0i64) w else grow(k - 1i64, w + 1)
}
