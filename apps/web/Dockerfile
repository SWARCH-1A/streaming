FROM node:24-alpine AS dependencies
WORKDIR /workspace/apps/web
RUN npm install --global pnpm@11.17.0
COPY apps/web/package.json apps/web/pnpm-lock.yaml apps/web/pnpm-workspace.yaml apps/web/.npmrc ./
RUN pnpm install --frozen-lockfile
FROM dependencies AS check
COPY contracts/generated /workspace/contracts/generated
COPY apps/web ./
FROM check AS build
RUN pnpm build && pnpm prune --prod

# SPEC-18: an SSR runtime, not a static file server. Renders each request and serves the hashed assets.
FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /workspace/apps/web/package.json ./
COPY --from=build /workspace/apps/web/node_modules ./node_modules
COPY --from=build /workspace/apps/web/build ./build
COPY --from=build /workspace/apps/web/server.mjs ./
USER node
EXPOSE 3443
CMD ["node", "server.mjs"]
