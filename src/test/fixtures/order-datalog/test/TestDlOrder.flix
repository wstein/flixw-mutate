mod TestDlOrder {
    use Assert.assertEq;

    // The same call twice: a mutant of double changes both results alike, so this test never fails under a mutant.
    def spin(n: Int32, acc: Int32): Int32 = if (n <= 0) acc else spin(n - 1, DlOrder.double(acc) + n)

    @Test
    def testA1Slow(): Unit \ Assert = assertEq(expected = spin(30000000, 1), spin(30000000, 1))

    @Test
    def testB2Reach(): Unit \ Assert = assertEq(expected = 1 :: 2 :: 3 :: Nil, Vector.toList(DlOrder.reachable()))

    @Test
    def testB3Double(): Unit \ Assert = assertEq(expected = 6, DlOrder.double(3))
}
