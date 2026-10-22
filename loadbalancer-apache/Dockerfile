# Apache httpd 2.4.69 (Debian, glibc). Do NOT use the "-alpine" tags: the docs say OneAgent
# code-module support has limits on Alpine (musl) containers.
# Check the OneAgent support table for Apache HTTP Server before you change this tag.
FROM httpd:2.4.69

ENV FRONTEND_UPSTREAM="frontend:3000"

COPY httpd.conf /usr/local/apache2/conf/httpd.conf
COPY htdocs/ /usr/local/apache2/htdocs-miftah/

# Fail the build if the configuration has a syntax error.
RUN httpd -t

EXPOSE 8080
# The base image already starts: httpd-foreground
