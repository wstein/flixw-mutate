mod TestCalc {
    use Assert.assertEq;

    @Test
    def testClamp(): Unit \ Assert =
        assertEq(expected = 0, Calc.clamp(0, 10, -5));
        assertEq(expected = 10, Calc.clamp(0, 10, 15));
        assertEq(expected = 5, Calc.clamp(0, 10, 5))

    @Test
    def testSumTo(): Unit \ Assert = assertEq(expected = 6, Calc.sumTo(3))

    @Test
    def testCount(): Unit \ Assert = assertEq(expected = 5, Calc.count(5, 0))

    @Test
    def testParity(): Unit \ Assert =
        assertEq(expected = true, Calc.isEven(4));
        assertEq(expected = false, Calc.isEven(3));
        assertEq(expected = true, Calc.isOdd(3))

    @Test
    def testInRange(): Unit \ Assert =
        assertEq(expected = true, Calc.inRange(1, 3, 2));
        assertEq(expected = false, Calc.inRange(1, 3, 0));
        assertEq(expected = false, Calc.inRange(1, 3, 4))

    @Test
    def testAbs(): Unit \ Assert =
        assertEq(expected = 3, Calc.abs2(-3));
        assertEq(expected = 3, Calc.abs2(3));
        assertEq(expected = 0, Calc.abs2(0))

    @Test
    def testText(): Unit \ Assert =
        assertEq(expected = "hi you", Calc.greet("you"));
        assertEq(expected = 6.0f64, Calc.scale(2.0f64, 3.0f64))

    @Test
    def testBigQuotient(): Unit \ Assert =
        assertEq(expected = false, Calc.bigQuotient(false, 0));
        assertEq(expected = true, Calc.bigQuotient(true, 5));
        assertEq(expected = false, Calc.bigQuotient(true, 20))
}
