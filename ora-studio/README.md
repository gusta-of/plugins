# Ora Studio

Cliente (SGBD) para Oracle em Kotlin/Swing, no estilo do DataGrip/DBeaver, com **navegação por Ctrl+clique**.
O mesmo código corre como programa independente e como plugin do IntelliJ (`../ora-studio-plugin`).

## Arrancar

```bash
./run.sh            # compila na primeira vez (precisa de Java 17+) e abre a aplicação
```

Ou `./gradlew installDist` e `build/install/orastudio/bin/orastudio`. Tema claro: `ORASTUDIO_THEME=light ./run.sh`.

## O que faz

- **Ligações**: formulário como o "Data Sources" do IntelliJ (SID / Service name / URL, driver, utilizador, palavra-passe cifrada, "Testar ligação").
- **Drivers JDBC**: qualquer `ojdbc` (6, 7, 8, 10, 11…) escolhido por ti; deteta os de `~/.m2` ou descarrega o `ojdbc8`.
- **Navegador**: Ligação › Schema › Tabelas / Views / Materialized views / Procedures / Functions / Packages / Triggers / Types / Sequences / Synonyms.
- **Visualizador de objecto** (abas que carregam ao serem abertas): colunas, constraints, índices, triggers, **DDL/código** (com busca), dados, dependências.
- **Ctrl+clique**: com `Ctrl` premido, os nomes que são objectos da BD ficam azuis e sublinhados; clicar abre-os. Várias declarações → "Escolher declaração".
- **Consola SQL**: `Ctrl+Enter` (instrução), `Ctrl+Shift+Enter`/`F5` (script), `DBMS_OUTPUT`, erros ORA realçados, cancelar, commit/rollback, autocompletar enquanto escreves.
- **Abas**: renomear, fechar (×, botão do meio, `Ctrl+W`), e **desacoplar para uma janela independente** (clique direito › *Desacoplar*, botão ⇱ ou `Ctrl+Shift+D`).
  Fechar a janela volta a acoplar a aba no mesmo sítio.

## Onde guarda coisas

`~/.config/orastudio/` (Windows: `%APPDATA%\OraStudio`): `connections.json`, `drivers.json`, `prefs.json`, `secret.key`, os textos das consolas, `backups/` (cópias automáticas)
e `orastudio.log`. Muda a pasta com `ORASTUDIO_HOME`. As ligações são partilhadas com o plugin do IntelliJ e com a extensão do VS Code.

## Testes

```bash
./gradlew test                               # unitários
./scripts/oracle-test-container.sh start     # Oracle 11.2 XE descartável (precisa de Docker)
ORA_TEST_JAR=~/.m2/repository/com/oracle/database/jdbc/ojdbc8/19.17.0.0/ojdbc8-19.17.0.0.jar \
ORA_TEST_URL=jdbc:oracle:thin:@localhost:15211:XE ORA_TEST_USER=DEMO ORA_TEST_PASSWORD=demo_test \
  ./gradlew cleanTest test                   # + integração e renderização de UI (build/ui-shots/*.png)
```
