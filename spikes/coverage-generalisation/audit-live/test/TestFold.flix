mod TestFold {
    @Test
    def testLit(): Unit \ Assert = Assert.assertEq(expected = 6, Fold.lit(3))

    @Test
    def testKeep(): Unit \ Assert = Assert.assertEq(expected = 3, Fold.keep(3))
}
