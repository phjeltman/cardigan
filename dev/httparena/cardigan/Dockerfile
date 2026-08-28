FROM maven:3.9-eclipse-temurin-26 AS build

COPY lib /pg-java
RUN cd /pg-java \
    && sha256sum -c SHA256SUMS \
    && mvn -q install:install-file \
        -Dfile=postgresql-client-protocol-0.1.0-SNAPSHOT.jar \
        -DpomFile=postgresql-client-protocol.pom \
    && mvn -q install:install-file \
        -Dfile=postgresql-client-0.1.0-SNAPSHOT.jar \
        -DpomFile=postgresql-client.pom

WORKDIR /src
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY LICENSE ./
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=/deps

FROM eclipse-temurin:26-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 \
    && rm -rf /var/lib/apt/lists/*

ARG CARDIGAN_HTTPARENA_MODE=h1
ENV CARDIGAN_HTTPARENA_MODE=${CARDIGAN_HTTPARENA_MODE}

WORKDIR /app
COPY --from=build /src/target/cardigan-httparena.jar ./cardigan-httparena.jar
COPY --from=build /deps ./lib
COPY --from=build /pg-java/PG_JAVA_LICENSE ./lib/PG_JAVA_LICENSE

EXPOSE 8080 8081 8082 8443

ENTRYPOINT ["java", \
    "--enable-preview", \
    "--enable-native-access=ALL-UNNAMED", \
    "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
    "--add-modules", "jdk.incubator.vector", \
    "-Xms512m", "-Xmx2g", \
    "-XX:MaxInlineSize=150", \
    "-XX:FreqInlineSize=600", \
    "-Dcardigan.egress.buffers.per.loop=1024", \
    "-Dcardigan.tls.handshake.max.pending.per.loop=256", \
    "-cp", "/app/cardigan-httparena.jar:/app/lib/*", \
    "dev.cardigan.httparena.HttpArenaMain"]
