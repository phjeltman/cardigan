FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /src
COPY pom.xml ./
COPY LICENSE THIRD_PARTY_NOTICES.md ./
COPY LICENSES ./LICENSES
COPY src ./src
COPY dev/httparena/pom.xml ./dev/httparena/pom.xml
COPY dev/httparena/src ./dev/httparena/src
RUN mvn -q -DskipTests install
RUN mvn -q -f dev/httparena/pom.xml -DskipTests package

FROM eclipse-temurin:26-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 \
    && rm -rf /var/lib/apt/lists/*

ARG CARDIGAN_HTTPARENA_MODE=h1
ENV CARDIGAN_HTTPARENA_MODE=${CARDIGAN_HTTPARENA_MODE}

WORKDIR /app
COPY --from=build /src/target/cardigan-0.1.0-alpha1.jar ./cardigan.jar
COPY --from=build /src/dev/httparena/target/cardigan-httparena-0.1.0-SNAPSHOT.jar ./cardigan-httparena.jar

EXPOSE 8080 8081 8082 8443

ENTRYPOINT ["java", \
    "--enable-preview", \
    "--enable-native-access=ALL-UNNAMED", \
    "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
    "--add-modules", "jdk.incubator.vector", \
    "-Xms512m", "-Xmx512m", \
    "-XX:MaxInlineSize=150", \
    "-XX:FreqInlineSize=600", \
    "-cp", "/app/cardigan.jar:/app/cardigan-httparena.jar", \
    "dev.cardigan.httparena.HttpArenaMain"]
