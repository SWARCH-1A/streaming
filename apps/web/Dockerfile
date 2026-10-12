FROM node:24-alpine AS build
WORKDIR /workspace/apps/web
RUN npm install --global pnpm@11.17.0
COPY apps/web/package.json apps/web/pnpm-lock.yaml apps/web/pnpm-workspace.yaml apps/web/.npmrc ./
RUN pnpm install --frozen-lockfile
COPY contracts/generated /workspace/contracts/generated
COPY apps/web ./
RUN pnpm build

FROM caddy:2-alpine
COPY --from=build /workspace/apps/web/dist /srv
COPY apps/web/Caddyfile /etc/caddy/Caddyfile
EXPOSE 3443
