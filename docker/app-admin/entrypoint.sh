#!/bin/sh

# Start JWT validator in background
node /app/index.js &

# Start nginx in foreground
exec nginx -g 'daemon off;'
