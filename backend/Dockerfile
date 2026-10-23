FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-alpine AS runtime

WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
RUN apk add --no-cache su-exec \
    && npm ci --omit=dev \
    && npm cache clean --force
COPY --chown=node:node --from=build /app/dist/src ./dist/src
RUN mkdir -p /data && chown -R node:node /app /data

EXPOSE 8080
# Railway mounts the persistent volume after the image is built, so repair the
# mount ownership at startup and then immediately drop privileges to `node`.
CMD ["sh", "-c", "chown -R node:node /data && exec su-exec node node dist/src/index.js"]
