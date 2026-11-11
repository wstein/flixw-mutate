mod TestLimits {
    use Assert.assertEq;

    @Test
    def testBothFalse(): Unit \ Assert = assertEq(expected = false, Limits.both(false, 1))

    @Test
    def testEachSumEmpty(): Unit \ Assert = assertEq(expected = 0, Limits.eachSum(Nil))
}
