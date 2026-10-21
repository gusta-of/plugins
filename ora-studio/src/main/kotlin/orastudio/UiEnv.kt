package orastudio

/** Ponto de ligacao ao ambiente grafico: o programa independente usa o FlatLaf, o plugin do IntelliJ usa o JBUI. */
object UiEnv {
    @Volatile var isDark: () -> Boolean = { true }
    @Volatile var scale: (Int) -> Int = { it }
}
