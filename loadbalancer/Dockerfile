# nginx 1.30.4 is supported by OneAgent from version 1.345.
# Check the "Support for NGINX" table in the Dynatrace docs before you change this tag.
# The image is Debian (glibc), not Alpine, so OneAgent injection works smoothly.
FROM nginx:1.30.4

ENV FRONTEND_UPSTREAM="frontend:3000" \
    FRONTEND_PROXY_MODE="static" \
    NGINX_ENVSUBST_FILTER="^(NGINX_RESOLVER|FRONTEND_UPSTREAM|FRONTEND_PROXY_PASS)$"

COPY --chmod=0755 docker-entrypoint.d/05-resolver.envsh /docker-entrypoint.d/05-resolver.envsh
COPY templates/default.conf.template /etc/nginx/templates/default.conf.template
COPY errors/ /usr/share/nginx/miftah/errors/

EXPOSE 8080
