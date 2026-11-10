// The switch read by schemata sites. `unsafe IO` hides the Java state read from the type system.
mod Mutant {
    import java.lang.System

    pub def on(n: Int32): Bool =
        Int32.fromString(unsafe IO { System.getProperty("flixw.mutant", "0") }) |> Option.getWithDefault(0) == n
}
