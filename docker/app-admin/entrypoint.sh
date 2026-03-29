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

exit_code=0
while :; do
  if ! kill -0 "$jwt_pid" 2>/dev/null; then
    wait "$jwt_pid"
    exit_code=$?
    break
  fi

  if ! kill -0 "$nginx_pid" 2>/dev/null; then
    wait "$nginx_pid"
    exit_code=$?
    break
  fi

  sleep 1
done

shutdown
exit $exit_code
