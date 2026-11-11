mod TestWide {
    use Assert.assertEq;

    @Test
    def testSum(): Unit \ Assert = assertEq(expected = 10, Stats.sum(1 :: 2 :: 3 :: 4 :: Nil))

    @Test
    def testMax(): Unit \ Assert = assertEq(expected = 9, Stats.maxOf(3 :: 9 :: 2 :: Nil))

    @Test
    def testMean(): Unit \ Assert = assertEq(expected = 2, Stats.mean(1 :: 2 :: 3 :: Nil))

    @Test
    def testMeanEmpty(): Unit \ Assert = assertEq(expected = 0, Stats.mean(Nil))

    @Test
    def testSorted(): Unit \ Assert =
        assertEq(expected = true, Stats.isSorted(1 :: 2 :: 2 :: 5 :: Nil));
        assertEq(expected = false, Stats.isSorted(3 :: 2 :: Nil))

    @Test
    def testAbove(): Unit \ Assert = assertEq(expected = 2, Stats.countAbove(2, 1 :: 3 :: 5 :: Nil))

    @Test
    def testSpread(): Unit \ Assert = assertEq(expected = 4, Stats.spread(5 :: 1 :: 3 :: Nil))

    @Test
    def testArea(): Unit \ Assert = assertEq(expected = 12, Shapes.area(3, 4))

    @Test
    def testPerimeter(): Unit \ Assert = assertEq(expected = 14, Shapes.perimeter(3, 4))

    @Test
    def testSquare(): Unit \ Assert =
        assertEq(expected = true, Shapes.isSquare(3, 3));
        assertEq(expected = false, Shapes.isSquare(3, 4))

    @Test
    def testFits(): Unit \ Assert =
        assertEq(expected = true, Shapes.fits(2, 3, 5));
        assertEq(expected = false, Shapes.fits(6, 3, 5))

    @Test
    def testRatio(): Unit \ Assert =
        assertEq(expected = 150, Shapes.ratio(3, 2));
        assertEq(expected = 0, Shapes.ratio(3, 0))

    @Test
    def testGrow(): Unit \ Assert = assertEq(expected = 8, Shapes.grow(3i64, 5))

    @Test
    def testPad(): Unit \ Assert = assertEq(expected = "ab__", Text.pad(4, "ab"))

    @Test
    def testShort(): Unit \ Assert =
        assertEq(expected = true, Text.isShort("abc"));
        assertEq(expected = false, Text.isShort("abcd"))

    @Test
    def testSameLength(): Unit \ Assert = assertEq(expected = true, Text.sameLength("ab", "cd"))

    @Test
    def testLazy(): Unit \ Assert = assertEq(expected = 4, Text.lazyLen("abc"))

    @Test
    def testDivisible(): Unit \ Assert =
        assertEq(expected = true, Text.divisible(6, 3));
        assertEq(expected = false, Text.divisible(7, 3))

    @Test
    def testWithin(): Unit \ Assert =
        assertEq(expected = true, Helpers.within(3));
        assertEq(expected = false, Helpers.within(-1));
        assertEq(expected = false, Helpers.within(16))

    @Test
    def testLimitTwice(): Unit \ Assert = assertEq(expected = 30, Helpers.limit() + Helpers.limit())

    @Test
    def testSafeDiv(): Unit \ Assert =
        assertEq(expected = 3, Helpers.safeDiv(7, 2));
        assertEq(expected = 0, Helpers.safeDiv(7, 0))

    @Test
    def testHot(): Unit \ Assert = assertEq(expected = 1000499500i64, Hot.loop(1000000i64, 0i64))

    @Skip
    @Test
    def testSkipped(): Unit \ Assert = assertEq(expected = 1, 2)
}
