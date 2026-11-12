mod TestLearn {
    use Assert.assertEq;

    // Each test calls the same functions; only the last one checks a value.
    @Test
    def testC1Weak(): Unit \ Assert = assertEq(expected = Learn.f(1) + Learn.g(1) + Learn.h(1) + Learn.k(1), Learn.f(1) + Learn.g(1) + Learn.h(1) + Learn.k(1))

    @Test
    def testC2Weak(): Unit \ Assert = assertEq(expected = Learn.f(2) + Learn.g(2) + Learn.h(2) + Learn.k(2), Learn.f(2) + Learn.g(2) + Learn.h(2) + Learn.k(2))

    @Test
    def testC3Strong(): Unit \ Assert =
        assertEq(expected = 2, Learn.f(1));
        assertEq(expected = 2, Learn.g(1));
        assertEq(expected = -2, Learn.h(1));
        assertEq(expected = 5, Learn.k(1))
}
