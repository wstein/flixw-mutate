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

        c.test("a region and an expression start round-trip", () -> {
            Region r = new Region("/p/src/A b.flix", Point.Kind.ARM, new Span(10, 20), 3);
            eq(r, Protocol.decodeRegion(Protocol.decode(Protocol.encodeRegion(r))));
            Exec e = new Exec("/p/src/A b.flix", 12, 3);
            eq(e, Protocol.decodeExec(Protocol.decode(Protocol.encodeExec(e))));
        });

        c.test("a Datalog site round-trips, with tabs and backslashes in its key", () -> {
            DatalogSite d = new DatalogSite(Point.Kind.FACT, "/p/src/A b.flix", new Span(10, 22), 4, "Mod.def", "Name(\"a\\tb\")");
            eq(d, Protocol.decodeDatalogSite(Protocol.decode(Protocol.encodeDatalogSite(d))));
            DatalogSite r = new DatalogSite(Point.Kind.RULE, "/p/src/A.flix", new Span(0, 5), 1, "", "$Result<-Even");
            eq(r, Protocol.decodeDatalogSite(Protocol.decode(Protocol.encodeDatalogSite(r))));
        });

        c.test("a Datalog site has the kind rule or fact", () -> {
            throwsA(IllegalArgumentException.class, () -> new DatalogSite(Point.Kind.LINE, "/p/A.flix", new Span(0, 1), 1, "", "k"));
            throwsA(IllegalArgumentException.class, () -> new DatalogSite(Point.Kind.MUTANT, "/p/A.flix", new Span(0, 1), 1, "", "k"));
        });

        c.test("a point kind that is not a region kind is rejected", () -> {
            throwsA(IllegalArgumentException.class, () -> new Region("/p/A.flix", Point.Kind.MUTANT, new Span(0, 1), 1));
            throwsA(IllegalArgumentException.class, () -> new Region("/p/A.flix", Point.Kind.LINE, new Span(0, 1), 1));
        });

        c.test("an unknown kind is rejected", () -> {
            throwsA(IllegalArgumentException.class,
                () -> Protocol.decodeSite(Protocol.decode("site\tf\tOrder.nope\t1\t2\t1\t2\t-1\t-1")));
        });

        c.finish();
    }
}
