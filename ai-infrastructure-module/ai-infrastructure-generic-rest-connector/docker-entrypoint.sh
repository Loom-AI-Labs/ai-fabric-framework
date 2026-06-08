#!/bin/sh
set -eu

CONNECTOR_UID="${CONNECTOR_UID:-10002}"

if [ "$(id -u)" = "0" ]; then
  mkdir -p /config
  chown -R "${CONNECTOR_UID}:0" /config || true
  exec gosu "${CONNECTOR_UID}:0" sh -c "exec java ${JAVA_OPTS:-} -jar /app/connector.jar \"$@\"" -- "$@"
fi

exec sh -c "exec java ${JAVA_OPTS:-} -jar /app/connector.jar \"$@\"" -- "$@"

