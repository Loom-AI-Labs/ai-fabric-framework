#!/bin/sh
set -eu

RUNTIME_UID="${RUNTIME_UID:-10001}"
RUNTIME_USER="${RUNTIME_USER:-runtime}"

# Ensure mounted volumes are writable by the runtime user.
if [ "$(id -u)" = "0" ]; then
  mkdir -p /data /app/data /config
  chown -R "${RUNTIME_UID}:0" /data /app/data /config || true
  exec gosu "${RUNTIME_UID}:0" sh -c "exec java ${JAVA_OPTS:-} -jar /app/runtime.jar \"$@\"" -- "$@"
fi

exec sh -c "exec java ${JAVA_OPTS:-} -jar /app/runtime.jar \"$@\"" -- "$@"
