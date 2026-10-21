#!/usr/bin/env bash
# Gera o build de todos os projetos de priv/ e junta os artefactos em priv/dist/.
#   ./build-all.sh              tudo
#   ./build-all.sh vscode       só as extensões VS Code   (grupos: ora-studio, server, plugins, vscode)
# Precisa de Java 17+ (para o Gradle) e de Node 18+ com npm (para as extensões VS Code).
# Variáveis: ORASTUDIO_JAVA_HOME, NODE_HOME.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
DIST="$ROOT/dist"

pick_java() {
  for j in "${ORASTUDIO_JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-25-openjdk-amd64 "${JAVA_HOME:-}"; do
    [ -n "$j" ] && [ -x "$j/bin/java" ] && "$j/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])' && { echo "$j"; return; }
  done
  return 1
}
JAVA_HOME="$(pick_java)" || { echo "Precisas de Java 17+ (define ORASTUDIO_JAVA_HOME)" >&2; exit 1; }
export JAVA_HOME
[ -n "${NODE_HOME:-}" ] && export PATH="$NODE_HOME/bin:$PATH"

GROUPS_WANTED=("$@")
sel() { [ ${#GROUPS_WANTED[@]} -eq 0 ] && return 0; for g in "${GROUPS_WANTED[@]}"; do [ "$g" = "$1" ] && return 0; done; return 1; }

mkdir -p "$DIST"
step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
gradle() { (cd "$1" && shift && ./gradlew --console=plain -q "$@"); }

if sel ora-studio; then
  step "Ora Studio (Swing, standalone)"
  gradle ora-studio installDist
  cp ora-studio/build/libs/orastudio-*.jar "$DIST/" 2>/dev/null || true
  rm -f "$DIST/orastudio-app.zip"
  (cd ora-studio/build/install && zip -qr "$DIST/orastudio-app.zip" orastudio)
fi

if sel server || sel vscode; then
  step "Servidor auxiliar do Ora Studio (JSON-RPC)"
  gradle ora-studio-server serverJar
  cp ora-studio-server/build/libs/orastudio-server.jar ora-studio-vscode/server/
fi

if sel plugins; then
  step "Plugin IntelliJ: Ora Studio"
  gradle ora-studio-plugin buildPlugin
  cp ora-studio-plugin/build/distributions/*.zip "$DIST/"
  step "Plugin IntelliJ: PEM WL Tools"
  gradle pem-tools buildPlugin
  cp pem-tools/build/distributions/*.zip "$DIST/"
fi

if sel vscode; then
  command -v npm >/dev/null || { echo "npm não encontrado (instala Node ou define NODE_HOME)" >&2; exit 1; }
  for p in pem-tools-vscode ora-studio-vscode; do
    step "Extensão VS Code: $p"
    (cd "$p" && { [ -d node_modules ] || npm install --no-audit --no-fund; } && npm run package --silent)
    cp "$p"/*.vsix "$DIST/"
  done
fi

step "Concluído — artefactos em $DIST"
ls -lh "$DIST"
