#!/usr/bin/env bash
# Volta a copiar as dependências resolvidas pelo Gradle para esta pasta.
set -euo pipefail
cd "$(dirname "$0")"; LIBS="$PWD"
JAVA_HOME="${ORASTUDIO_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"; export JAVA_HOME
rm -rf orastudio ora-studio-server orastudio-plugin pem-tools all
for p in ora-studio ora-studio-server ora-studio-plugin; do
  (cd "../$p" && ./gradlew -q --console=plain -I "$LIBS/export-libs.init.gradle" exportLibs -PlibsOut="$LIBS")
done
mkdir -p all
find . -path ./all -prune -o -name '*.jar' -print | xargs -I{} cp --update=none {} all/
echo "OK: $(ls all | wc -l) JARs em $LIBS/all"
