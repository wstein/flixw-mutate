mod TestFacts {
    use Assert.assertEq;

    @Test
    def testNumbers(): Unit \ Assert = assertEq(expected = 1 :: 2 :: 3 :: Nil, Vector.toList(DlFacts.numbers()))

    @Test
    def testDouble(): Unit \ Assert = assertEq(expected = 6, DlFacts.double(3))
}
