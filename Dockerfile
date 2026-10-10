FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -d out src/sportground/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY public ./public
CMD ["java", "-cp", "out", "sportground.App"]
