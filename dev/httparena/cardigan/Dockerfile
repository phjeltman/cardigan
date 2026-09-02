FROM debian:bookworm-slim AS toolchain

ARG OPENJDK_URL=https://download.java.net/java/GA/jdk27/55ce5470a6294008af0057ff4626d0e5/35/GPL/openjdk-27_linux-x64_bin.tar.gz
ARG OPENJDK_SHA256=95fc37eb3a18a27a26d5904c2d89d52bace8dafa9a078ca27f4747fbc4bf070b
ARG MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
ARG MAVEN_SHA512=bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && curl -fL --retry 3 "$OPENJDK_URL" -o /tmp/openjdk.tar.gz \
    && echo "$OPENJDK_SHA256  /tmp/openjdk.tar.gz" | sha256sum -c - \
    && mkdir /opt/openjdk \
    && tar -xzf /tmp/openjdk.tar.gz -C /opt/openjdk --strip-components=1 \
    && curl -fL --retry 3 "$MAVEN_URL" -o /tmp/maven.tar.gz \
    && echo "$MAVEN_SHA512  /tmp/maven.tar.gz" | sha512sum -c - \
    && mkdir /opt/maven \
    && tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1 \
    && rm -rf /tmp/openjdk.tar.gz /tmp/maven.tar.gz \
        /var/lib/apt/lists/*

FROM toolchain AS build

ENV JAVA_HOME=/opt/openjdk
ENV MAVEN_HOME=/opt/maven
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"

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

FROM debian:bookworm-slim

COPY --from=toolchain /opt/openjdk /opt/openjdk
ENV JAVA_HOME=/opt/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3 \
    && rm -rf /var/lib/apt/lists/*

ARG CARDIGAN_HTTPARENA_MODE=h1
ENV CARDIGAN_HTTPARENA_MODE=${CARDIGAN_HTTPARENA_MODE}

WORKDIR /app
COPY --from=build /src/target/cardigan-httparena.jar ./cardigan-httparena.jar
COPY --from=build /deps ./lib

EXPOSE 8080 8081 8082 8443

ENTRYPOINT ["java", \
    "--enable-preview", \
    "--enable-native-access=ALL-UNNAMED", \
    "--add-opens", "java.base/java.lang=ALL-UNNAMED", \
    "--add-modules", "jdk.incubator.vector", \
    "-Xms512m", "-Xmx2g", \
    "-XX:MaxInlineSize=150", \
    "-XX:FreqInlineSize=600", \
    "-Dcardigan.tls.handshake.max.pending.per.loop=256", \
    "-cp", "/app/cardigan-httparena.jar:/app/lib/*", \
    "dev.cardigan.httparena.HttpArenaMain"]
