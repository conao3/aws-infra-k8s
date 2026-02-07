#!/bin/sh
set -e

shutdown() {
  echo "Shutting down..."
  kill -TERM "$jwt_pid" "$nginx_pid" 2>/dev/null || true
  wait
}

trap shutdown SIGTERM SIGINT

node /app/index.js &
jwt_pid=$!

nginx -g 'daemon off;' &
nginx_pid=$!

wait -n
exit_code=$?

shutdown
exit $exit_code
