mod TestPairs {
    use Assert.assertEq;

    @Test
    def testSum(): Unit \ Assert = assertEq(expected = 3, Pairs.sum((1, 2)))

    @Test
    def testPickSome(): Unit \ Assert = assertEq(expected = 4, Pairs.pick(Some(4)))
}
