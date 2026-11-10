mod TestReachGraph {
    use Assert.assertEq;

    @Test
    def testReach(): Unit \ Assert =
        let (r, _, _) = ReachGraph.analyse();
        assertEq(expected = Vector#{1, 2, 3, 4}, r)

    @Test
    def testUnreach(): Unit \ Assert =
        let (_, u, _) = ReachGraph.analyse();
        assertEq(expected = Vector#{5, 6}, u)

    @Test
    def testPair(): Unit \ Assert =
        let (_, _, p) = ReachGraph.analyse();
        assertEq(expected = Vector#{(1, 2), (2, 3), (3, 4)}, p)
}
