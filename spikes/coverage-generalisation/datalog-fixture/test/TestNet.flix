mod TestNet {
    use Assert.assertEq;

    @Test
    def testReachable(): Unit \ Assert = assertEq(expected = 1 :: 2 :: 3 :: Nil, Vector.toList(DlNet.reachable()))

    @Test
    def testEvens(): Unit \ Assert = assertEq(expected = 2 :: 4 :: Nil, Vector.toList(DlNet.evens()))

    @Test
    def testDouble(): Unit \ Assert = assertEq(expected = 6, DlNet.double(3))
}
