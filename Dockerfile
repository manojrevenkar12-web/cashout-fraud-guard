# Build: ./gradlew :service:installDist, then docker build .
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 guard
COPY service/build/install/cashout-fraud-guard /app
USER 10001
ENV PORT=50051
EXPOSE 50051
ENTRYPOINT ["/app/bin/cashout-fraud-guard"]
