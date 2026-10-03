FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
RUN chmod +x mvnw && ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /data/avatars /data/banners && chown -R 10001:10001 /data/avatars /data/banners
COPY --from=build /build/target/core.jar /app/core.jar
ENV PROFILE_AVATAR_STORAGE=/data/avatars
ENV CHANNELS_BANNER_STORAGE=/data/banners
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/core.jar"]
