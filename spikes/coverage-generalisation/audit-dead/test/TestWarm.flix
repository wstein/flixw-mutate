mod TestWarm {
    @Test
    def testTwice(): Unit \ Assert = Assert.assertEq(expected = 6, Warm.twice(3))
}
