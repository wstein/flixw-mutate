package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.List;

/**
 * The line protocol between the engine and the worker JVM. A message is one line: a verb and fields,
 * separated by tabs; backslash, tab, line feed and carriage return inside a field are escaped.
 */
final class Protocol {
    private Protocol() { }

    record Message(String verb, List<String> fields) {
        String field(int i) { return fields.get(i); }
    }

    static String encode(String verb, String... fields) {
        StringBuilder sb = new StringBuilder(escape(verb));
        for (String f : fields) sb.append('\t').append(escape(f));
        return sb.toString();
    }

    static Message decode(String line) {
        String[] parts = line.split("\t", -1);
        List<String> fields = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) fields.add(unescape(parts[i]));
        return new Message(unescape(parts[0]), List.copyOf(fields));
    }

    static String encodeSite(Site s) {
        Span r = s.right();
        return encode("site", s.file(), s.kind().typedAstName(), String.valueOf(s.whole().start()),
            String.valueOf(s.whole().end()), String.valueOf(s.left().start()), String.valueOf(s.left().end()),
            String.valueOf(r == null ? -1 : r.start()), String.valueOf(r == null ? -1 : r.end()));
    }

    static Site decodeSite(Message m) {
        SiteKind kind = SiteKind.byTypedAstName(m.field(1))
            .orElseThrow(() -> new IllegalArgumentException("unknown site kind " + m.field(1)));
        int rs = Integer.parseInt(m.field(6));
        Span right = rs < 0 ? null : new Span(rs, Integer.parseInt(m.field(7)));
        return new Site(m.field(0), kind, new Span(Integer.parseInt(m.field(2)), Integer.parseInt(m.field(3))),
            new Span(Integer.parseInt(m.field(4)), Integer.parseInt(m.field(5))), right);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
