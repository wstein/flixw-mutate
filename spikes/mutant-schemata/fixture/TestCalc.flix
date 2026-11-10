mod TestCalc {
    use Assert.assertEq;

    @Test
    def testClamp(): Unit \ Assert =
        assertEq(expected = 0, Calc.clamp(0, 10, -5));
        assertEq(expected = 10, Calc.clamp(0, 10, 15));
        assertEq(expected = 5, Calc.clamp(0, 10, 5));
        assertEq(expected = 0, Calc.clamp(0, 10, 0));
        assertEq(expected = 10, Calc.clamp(0, 10, 10))

    @Test
    def testClampLow(): Unit \ Assert =
        assertEq(expected = 0, Calc.clampLow(0, -1));
        assertEq(expected = 5, Calc.clampLow(0, 5))

    @Test
    def testClampHigh(): Unit \ Assert =
        assertEq(expected = 3, Calc.clampHigh(3, 9));
        assertEq(expected = 2, Calc.clampHigh(3, 2))

    @Test
    def testSumTo(): Unit \ Assert = assertEq(expected = 6, Calc.sumTo(3))

    @Test
    def testCount(): Unit \ Assert = assertEq(expected = 5, Calc.count(5, 0))

    @Test
    def testFirstOrZero(): Unit \ Assert =
        assertEq(expected = 7, Calc.firstOrZero(7 :: 8 :: Nil));
        assertEq(expected = 0, Calc.firstOrZero(Nil))

    @Test
    def testIsEven(): Unit \ Assert =
        assertEq(expected = true, Calc.isEven(4));
        assertEq(expected = false, Calc.isEven(3))

    @Test
    def testAbs2(): Unit \ Assert =
        assertEq(expected = 3, Calc.abs2(-3));
        assertEq(expected = 3, Calc.abs2(3));
        assertEq(expected = 0, Calc.abs2(0))

    @Test
    def testLabel(): Unit \ Assert =
        assertEq(expected = "pos", Calc.label(1));
        assertEq(expected = "neg", Calc.label(-1))
}
