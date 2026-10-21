# PEM WL Tools (VS Code)

Painel lateral para limpar caches, compilar, fazer deploy e arrancar/parar o WebLogic do PEM — o mesmo que o plugin do IntelliJ,
em cima dos scripts de `.vscode/scripts`.

- **Campos** (guardados nas definições do utilizador, sobrevivem a atualizações): *Workspace*, *AdminServer* (domínio WebLogic), perfil Maven,
  *Pular testes* (`-Dmaven.test.skip=true`), *localMock* (`-PlocalMock` só no `pem-common-domain`) e *Threads (-T)* do build frontend rápido. Vêm vazios.
- **WebLogic:** Start, Stop (gracioso, máx. 60 s, e depois **força** a paragem) e Kill. Funciona em Linux e Windows (`.sh` / `.cmd`).
- **Limpeza:** cache/classpath (WL, `target/`, `~/.m2/.../modules/pem` poupando `fonts`, autodeploy poupando `marketplace-portlet` e `pem-layouttpl`), só `target/`, matar JARs dos módulos.
- **Build:** Backend, Frontend, Frontend rápido, e módulos isolados (escolha múltipla).
- **Deploy:** Backend, Frontend e módulos isolados (escolha múltipla).
- Os botões ficam **vermelhos e bloqueados** enquanto o processo que iniciaram corre; barra de progresso com percentagem (esconde-se 5 s depois do fim).
- Logs em *Saída*: «PEM WL — Tarefas» e «PEM WL — WebLogic» (use o filtro do painel Saída para procurar).
- Também há comandos na paleta (`PEM WL: …`).

## Desenvolver

```bash
npm install
npm test            # testes (Node test runner + tsx)
npm run package     # gera pem-wl-tools-1.0.0.vsix
```

Instalar: `code --install-extension pem-wl-tools-1.0.0.vsix` (ou *Extensions → … → Install from VSIX…*).
