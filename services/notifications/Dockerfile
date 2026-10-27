FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY migrations ./migrations
COPY src ./src
USER node
EXPOSE 8095
# Node 24 runs the TypeScript sources directly (type stripping); typecheck and tests run in CI/dev.
CMD ["node", "src/main.ts"]
