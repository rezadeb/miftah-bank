# nginx 1.30.4: didukung penuh oleh OneAgent mulai versi 1.345 (cek tabel "Support for NGINX" di docs Dynatrace).
# Image Debian (glibc), bukan Alpine, supaya injeksi OneAgent berjalan mulus.
FROM nginx:1.30.4

ENV FRONTEND_UPSTREAM="frontend:3000" \
    NGINX_ENVSUBST_FILTER="^(NGINX_RESOLVER|FRONTEND_UPSTREAM)$"

COPY --chmod=0755 docker-entrypoint.d/05-resolver.envsh /docker-entrypoint.d/05-resolver.envsh
COPY templates/default.conf.template /etc/nginx/templates/default.conf.template
COPY errors/ /usr/share/nginx/miftah/errors/

EXPOSE 8080
