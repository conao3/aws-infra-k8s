FROM node:22-alpine AS builder
WORKDIR /build
COPY jwt-validator/package.json .
RUN npm install --production

FROM nginx:1.27-alpine
RUN apk add --no-cache nodejs=22.11.0-r0 tini
COPY --from=builder /build/node_modules /app/node_modules
COPY jwt-validator/index.js /app/index.js
COPY html/ /usr/share/nginx/html/
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 80
ENTRYPOINT ["/sbin/tini", "--", "/entrypoint.sh"]
