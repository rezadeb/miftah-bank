# Node.js 24 LTS di Debian (glibc) - bukan Alpine, supaya injeksi OneAgent berjalan mulus.
FROM node:24.22.0-trixie-slim
ENV NODE_ENV=production
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.js ./
COPY views ./views
COPY public ./public

USER node
EXPOSE 3000
CMD ["node", "server.js"]
