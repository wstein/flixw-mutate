package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;

import java.util.List;

public final class ProtocolTest {
    public static void main(String[] args) {
        Check c = new Check("ProtocolTest");

        c.test("a message is one line with tab-separated fields", () -> eq("run\t7", Protocol.encode("run", "7")));

        c.test("a verb alone", () -> eq(new Protocol.Message("quit", List.of()), Protocol.decode(Protocol.encode("quit"))));

        c.test("tab, line feed, carriage return and backslash survive a round trip", () -> {
            String nasty = "a\tb\nc\rd\\e\\n";
            String line = Protocol.encode("error", nasty, "x");
            eq(false, line.contains("\n") || line.contains("\r"));
            Protocol.Message m = Protocol.decode(line);
            eq("error", m.verb());
            eq(List.of(nasty, "x"), m.fields());
        });

        c.test("empty fields are kept", () -> {
            Protocol.Message m = Protocol.decode(Protocol.encode("result", "", "", ""));
            eq(List.of("", "", ""), m.fields());
        });

        c.test("a site with two operands round-trips", () -> {
            Site s = new Site("/p/src/A.flix", SiteKind.LESS, new Span(10, 20), new Span(10, 11), new Span(19, 20));
            eq(s, Protocol.decodeSite(Protocol.decode(Protocol.encodeSite(s))));
        });

        c.test("a site with one operand round-trips", () -> {
            Site s = new Site("/p/src/A b.flix", SiteKind.NOT, new Span(10, 20), new Span(14, 20), null);
            eq(s, Protocol.decodeSite(Protocol.decode(Protocol.encodeSite(s))));
        });

        c.test("an unknown kind is rejected", () -> {
            throwsA(IllegalArgumentException.class,
                () -> Protocol.decodeSite(Protocol.decode("site\tf\tOrder.nope\t1\t2\t1\t2\t-1\t-1")));
        });

        c.finish();
    }
}
