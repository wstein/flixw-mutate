package dev.flixw.plugin;

import dev.flixw.sdk.kit.Context;
import dev.flixw.sdk.kit.ExitCodes;
import dev.flixw.sdk.kit.Usage;
import java.util.Map;

/** flixw plugin entry point; the shell (context, exit codes) comes from the SDK kit. */
public final class PluginMain {
    private PluginMain() { }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> environment) {
        try {
            if (!"1".equals(environment.get("FLIXW_ABI_VERSION")))
                throw new Usage("requires flixw ABI version 1");
            if (args.length == 1 && "--help".equals(args[0])) {
                System.out.println("usage: ./flixw mutate [args...]");
                return ExitCodes.SUCCESS;
            }
            if (args.length == 1 && "--version".equals(args[0])) {
                System.out.println(Header.line(Header.pluginVersion(), Header.sdkRevision(), null));
                return ExitCodes.SUCCESS;
            }
            Context context = Context.read(environment);
            // flixVersion comes from the flixw ABI, not from lock.toml. It is absent without a lock.
            String flix = environment.get("FLIXW_COMPILER_VERSION");
            if (flix == null || flix.isBlank())
                throw new Usage("missing FLIXW_COMPILER_VERSION (the project has no lock)");
            System.out.println(Header.line(Header.pluginVersion(), Header.sdkRevision(), flix));
            System.out.println("project root: " + context.projectRoot());
            return ExitCodes.SUCCESS;
        } catch (Usage failure) {
            System.err.println("flixw-mutate: " + failure.getMessage());
            return ExitCodes.ERROR;
        }
    }
}
