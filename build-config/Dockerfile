FROM gradle:8.5-jdk21-jammy AS build

WORKDIR "/home/gradle/build"
COPY build.gradle .
COPY gradlew .
COPY gradlew.bat .
COPY settings.gradle .
COPY gradle/ .
COPY src/ .

# Build the shadow-jar which contains ALL dependencies
RUN gradle shadowJar --no-daemon -Dorg.gradle.welcome=never

# We can probably use a smaller base? Eg we don't need all the archs on GCP.
# But for now– we're doing this.
FROM eclipse-temurin:21-jre-jammy

RUN \
    apt-get update -y && \
    apt-get install -y gpg && \
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg && \
    apt-get update -y -o Dir::Etc::sourcelist="sources.list.d/google-cloud-sdk.list" \
                      -o Dir::Etc::sourceparts="-" -o APT::Get::List-Cleanup="0" && \
    apt-get install -y --no-install-recommends git google-cloud-sdk google-cloud-cli pigz openjfx python3 && \
    rm -rf /var/lib/apt/lists/* && \
    gcloud config set storage/parallel_composite_upload_enabled True

RUN mkdir /app

COPY --from=build /home/gradle/build/build/libs/*-all.jar /app/

ENTRYPOINT ["java","-jar","/app/qupath-measurement-1.0-SNAPSHOT-all.jar"]
