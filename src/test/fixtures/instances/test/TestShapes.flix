mod TestShapes {
    use Assert.assertEq;
    use Shapes.Box;
    use Shapes.Sized;

    @Test
    def testBoxSize(): Unit \ Assert =
        assertEq(expected = 0, Sized.size(Box.Box(-4)));
        assertEq(expected = 8, Sized.size(Box.Box(4)))

    @Test
    def testOptionSize(): Unit \ Assert =
        let none: Option[Box] = None;
        assertEq(expected = 0, Sized.size(none));
        assertEq(expected = 7, Sized.size(Some(Box.Box(4))))

    @Test
    def testIsBig(): Unit \ Assert =
        assertEq(expected = true, Sized.isBig(Box.Box(6)));
        assertEq(expected = false, Sized.isBig(Box.Box(2)))

    @Test
    def testTwice(): Unit \ Assert = assertEq(expected = 8, Sized.twice(Box.Box(2)))

    @Test
    def testCounted(): Unit \ Assert = assertEq(expected = 6, Shapes.counted())
}
