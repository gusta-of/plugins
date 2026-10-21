# Ora Studio (VS Code)

Cliente Oracle para o VS Code: várias ligações (ojdbc6/8/11), consolas SQL, Ctrl+clique para o DDL de tabelas/views/MVs/pacotes, autocompletar, estrutura de objetos (colunas, constraints, índices, triggers, dados, dependências) e pesquisa de objetos.

- Requer **Java 11+** (configurável em `oraStudio.javaPath`); o acesso Oracle é feito por um processo auxiliar (`server/orastudio-server.jar`).
- As ligações são guardadas em `~/.config/orastudio` (partilhadas com o Ora Studio Swing e o plugin IntelliJ).
- Configure o driver em *Ora Studio → Drivers*.
- Ctrl+Enter executa a instrução; Ctrl+Shift+Enter o script. As abas do VS Code podem ser arrastadas para uma janela nova.

Instalar: `code --install-extension ora-studio-1.0.0.vsix`
