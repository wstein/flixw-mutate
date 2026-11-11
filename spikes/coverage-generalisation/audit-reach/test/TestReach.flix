mod TestReach {
    @Test
    def testArea(): Unit \ Assert = Assert.assertEq(expected = 6, Geo.Area.area(5))

    @Test
    def testOuter(): Unit \ Assert = Assert.assertEq(expected = 4, Nested.outer(3))

    @Test
    def testOnly(): Unit \ Assert = Assert.assertEq(expected = 6, Direct.only(3))
}
