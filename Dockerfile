FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY knowledge/pom.xml knowledge/pom.xml
COPY importer/pom.xml importer/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY extraction/pom.xml extraction/pom.xml
COPY discovery/pom.xml discovery/pom.xml
COPY initiative/pom.xml initiative/pom.xml
COPY app/pom.xml app/pom.xml
COPY common/src common/src
COPY knowledge/src knowledge/src
COPY importer/src importer/src
COPY gateway/src gateway/src
COPY extraction/src extraction/src
COPY discovery/src discovery/src
COPY initiative/src initiative/src
COPY app/src app/src
ARG MAVEN_MIRROR_URL
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then mvn -B -s /usr/share/maven/ref/settings-docker.xml -Dmirror.url="$MAVEN_MIRROR_URL" verify; else mvn -B verify; fi
FROM eclipse-temurin:21-jre
COPY --from=build /workspace/app/target/app-0.1.0-SNAPSHOT.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
