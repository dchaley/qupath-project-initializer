FROM gradle:8.5-jdk21-jammy AS build

# Add the repo sha to the container as the version.
ADD https://api.github.com/repos/dchaley/qupath-project-initializer/git/refs/heads/main version.json

# Clone the repo
RUN git clone https://github.com/dchaley/qupath-project-initializer.git /home/gradle/src

WORKDIR "/home/gradle/src"

# Build the shadow-jar which contains ALL dependencies
RUN gradle shadowJar --no-daemon -Dorg.gradle.welcome=never

# We can probably use a smaller base? Eg we don't need all the archs on GCP.
# But for now– we're doing this.
FROM eclipse-temurin:21-jre-jammy

RUN mkdir /app

COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/

ENTRYPOINT ["java","-jar","/app/qupath-project-initializer-1.0-SNAPSHOT-all.jar"]
