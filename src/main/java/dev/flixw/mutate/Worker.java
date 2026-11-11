package dev.flixw.mutate;

/**
 * The worker JVM: the Flix compiler is on its class path. It answers the requests of {@link WorkerClient}
 * on stdin and stdout; everything the tests print goes to stderr.
 */
public final class Worker {
    private Worker() { }

    public static void main(String[] args) throws Exception {
        throw new UnsupportedOperationException("not implemented");
    }

    /** One line for a failure: class and first message line without terminal colour codes, at most 160 chars. */
    static String describe(Throwable t) {
        throw new UnsupportedOperationException("not implemented");
    }
}
