#!/usr/bin/env bash
# Oracle 11.2 XE descartavel para experimentar o Ora Studio e correr os testes de integracao.
#   ./scripts/oracle-test-container.sh start   -> sobe o contentor (porta 15211)
#   ./scripts/oracle-test-container.sh stop    -> apaga o contentor
# Ligacao no Ora Studio: host localhost, porta 15211, SID XE, utilizador DEMO, palavra-passe demo_test
set -euo pipefail
NAME=orastudio-test-xe
case "${1:-start}" in
  start)
    docker run -d --name "$NAME" -p 15211:1521 -e ORACLE_PASSWORD=orastudio_test -e APP_USER=DEMO -e APP_USER_PASSWORD=demo_test gvenzl/oracle-xe:11-slim
    echo "A aguardar o arranque (1-2 min)..."
    until docker logs "$NAME" 2>&1 | grep -q "DATABASE IS READY TO USE"; do sleep 3; done
    docker exec "$NAME" bash -c "sqlplus -s system/orastudio_test@XE <<'SQL'
GRANT CREATE VIEW, CREATE MATERIALIZED VIEW, CREATE TRIGGER, CREATE PROCEDURE, CREATE SEQUENCE, CREATE SYNONYM, CREATE TYPE, CREATE TABLE, QUERY REWRITE TO DEMO;
ALTER USER DEMO QUOTA UNLIMITED ON USERS;
CREATE USER OTHERS IDENTIFIED BY others_test QUOTA UNLIMITED ON USERS;
GRANT CREATE SESSION, CREATE TABLE TO OTHERS;
SQL"
    echo "Pronto: localhost:15211  SID=XE  DEMO/demo_test"
    ;;
  stop) docker rm -f "$NAME" ;;
esac
