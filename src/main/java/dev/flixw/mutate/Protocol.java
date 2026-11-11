package dev.flixw.mutate;

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
        throw new UnsupportedOperationException("not implemented");
    }

    static Message decode(String line) {
        throw new UnsupportedOperationException("not implemented");
    }

    static String encodeSite(Site site) {
        throw new UnsupportedOperationException("not implemented");
    }

    static Site decodeSite(Message message) {
        throw new UnsupportedOperationException("not implemented");
    }
}
