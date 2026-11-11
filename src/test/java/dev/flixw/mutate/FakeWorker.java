package dev.flixw.mutate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A stand-in worker for the client tests: echo, multi, hang and die. */
public final class FakeWorker {
    public static void main(String[] args) throws Exception {
        System.err.println("fake worker started");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            Protocol.Message m = Protocol.decode(line);
            switch (m.verb()) {
                case "echo" -> { System.out.println(Protocol.encode("echo", m.field(0))); System.out.println(Protocol.encode("done")); }
                case "multi" -> {
                    System.out.println(Protocol.encode("part", "1"));
                    System.out.println(Protocol.encode("part", "2"));
                    System.out.println(Protocol.encode("done"));
                }
                case "hang" -> Thread.sleep(600_000);
                case "die" -> System.exit(3);
                default -> System.out.println(Protocol.encode("error", "unknown " + m.verb()));
            }
            System.out.flush();
        }
    }
}
