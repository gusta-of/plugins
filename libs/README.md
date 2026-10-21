# libs — dependências dos projetos Kotlin (para builds offline / máquinas novas)

| Pasta | Conteúdo |
|---|---|
| `orastudio/runtime`, `ora-studio-server/runtime`, `orastudio-plugin/runtime` | JARs necessários para executar cada projeto (flatlaf, rsyntaxtextarea, gson, kotlin-stdlib…) |
| `*/test` | JARs só para testes (kotlin-test, junit…) |
| `drivers/` | ojdbc6, ojdbc8, ojdbc11 (Oracle) — escolha-os em «Drivers» no Ora Studio |
| `all/` | todos os JARs acima numa só pasta, sem duplicados (versões repetidas mantêm a primeira) |

Não inclui: o IntelliJ Platform 2024.1.7 (compilação dos plugins; vem do Gradle via `intellijPlatform`),
o Gradle 9.8.1 nem o compilador Kotlin — esses são descarregados pelo `gradlew` para `~/.gradle`.
Para um build 100 % offline use `./gradlew --offline` depois de um build online (a cache fica em `~/.gradle`).

Regenerar: `./export-libs.sh`
