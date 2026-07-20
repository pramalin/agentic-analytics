# Layers this project's own script on top of the published llmsim engine
# (Pattern A -- see llmsim's README, "Using llmsim in an app's end-to-end
# tests"). Nothing about llmsim's own source is copied into this repo;
# only this file and AnalyticsFlow.scala live here.

FROM ghcr.io/pramalin/llmsim-build:0.1.0 AS build
COPY AnalyticsFlow.scala /build/src/main/scala/com/example/agenticanalytics/llmsim/AnalyticsFlow.scala
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/scala-3.3.3/llmsim.jar /app/llmsim.jar
ENV LLMSIM_SCRIPT=com.example.agenticanalytics.llmsim.AnalyticsFlow
EXPOSE 8089
HEALTHCHECK --interval=2s --timeout=2s --retries=20 --start-period=5s \
  CMD curl --fail --silent http://localhost:8089/_llmsim/calls || exit 1
ENTRYPOINT ["java", "-jar", "/app/llmsim.jar"]
