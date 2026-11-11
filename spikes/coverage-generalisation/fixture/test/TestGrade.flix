mod TestGrade {
    use Assert.assertEq;

    @Test
    def testClassify(): Unit \ Assert =
        assertEq(expected = "negative", Grade.classify(-5));
        assertEq(expected = "zero", Grade.classify(0))

    @Test
    def testName(): Unit \ Assert =
        assertEq(expected = "zero", Grade.name(0));
        assertEq(expected = "many", Grade.name(7))

    @Test
    def testHalf(): Unit \ Assert =
        assertEq(expected = 4, Grade.half(Some(8)));
        assertEq(expected = -1, Grade.half(None))

    @Test
    def testTotalEmpty(): Unit \ Assert = assertEq(expected = 0, Grade.total(Nil))

    @Test
    def testFact(): Unit \ Assert = assertEq(expected = 6, Grade.fact(3))

    @Test
    def testStats(): Unit \ Assert = assertEq(expected = -5, Grade.stats(2, 3))

    @Test
    def testAbs(): Unit \ Assert = assertEq(expected = 4, Grade.abs(4))

    @Test
    def testGuarded(): Unit \ Assert = assertEq(expected = 7, Grade.guarded(7))

    @Test
    def testColor(): Unit \ Assert = assertEq(expected = "red", ToString.toString(Color.Red))
}
