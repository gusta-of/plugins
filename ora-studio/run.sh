#!/usr/bin/env bash
# Arranca o Ora Studio. Precisa de Java 17+ (o JAVA_HOME do shell pode apontar para o JDK 8 do WebLogic).
set -euo pipefail
cd "$(dirname "$0")"

pick_java() {
  for j in "${ORASTUDIO_JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-25-openjdk-amd64 "${JAVA_HOME:-}"; do
    [ -n "$j" ] && [ -x "$j/bin/java" ] && "$j/bin/java" -version 2>&1 | grep -qE 'version "(1[7-9]|[2-9][0-9])' && { echo "$j"; return; }
  done
  return 1
}

JAVA_HOME="$(pick_java)" || { echo "Precisas de Java 17+ (define ORASTUDIO_JAVA_HOME)" >&2; exit 1; }
export JAVA_HOME

if [ ! -x build/install/orastudio/bin/orastudio ]; then
  ./gradlew installDist --console=plain -q
fi
exec build/install/orastudio/bin/orastudio "$@"
