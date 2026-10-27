# 실행 중에는 설치/다운로드하지 않습니다. 도구 체인은 이미지 빌드 때만 준비합니다.
FROM node:24-bookworm-slim AS node
# 최종 채점 이미지에는 npm 자체가 필요 없습니다. 빌드 스테이지에서만 타입스크립트를 설치합니다.
RUN npm install --prefix /opt/typescript typescript@5.9.3 @types/node@24
FROM golang:1.26.8-bookworm AS go
# 공식 SDK의 고정 버전만 가져옵니다. 제출 시 pub get이나 SDK 다운로드는 하지 않습니다.
FROM dart:3.13.5-sdk AS dart
FROM mcr.microsoft.com/dotnet/sdk:10.0-noble
LABEL org.cobweb.judge.language-set="17-v1"
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /opt/typescript /opt/typescript
COPY --from=go /usr/local/go /usr/local/go
COPY --from=dart /usr/lib/dart /usr/lib/dart
# .main.kts 스크립트 확장은 지원하지 않으며 해당 부속 JAR의 취약한 Plexus 버전도 필요 없습니다.
RUN apt-get update && apt-get install -y --no-install-recommends \
       python3 gcc g++ openjdk-21-jdk-headless rustc ruby clang-18 fp-compiler php-cli curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://github.com/JetBrains/kotlin/releases/download/v2.2.21/kotlin-compiler-2.2.21.zip -o /tmp/kotlin.zip \
    && curl -fsSL https://github.com/JetBrains/kotlin/releases/download/v2.2.21/kotlin-compiler-2.2.21.zip.sha256 -o /tmp/kotlin.sha256 \
    && cd /tmp && printf '%s  kotlin.zip\n' "$(cat kotlin.sha256)" | sha256sum -c - \
    && unzip -q /tmp/kotlin.zip -d /opt && rm /tmp/kotlin.zip /tmp/kotlin.sha256 \
    && rm /opt/kotlinc/lib/kotlin-main-kts.jar
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 \
    PATH=/usr/lib/dart/bin:/usr/local/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    DOTNET_NOLOGO=1 DOTNET_CLI_HOME=/tmp/dotnet HOME=/tmp \
    NUGET_PACKAGES=/tmp/nuget GOCACHE=/tmp/go-cache GOPATH=/tmp/go GO111MODULE=off GOTOOLCHAIN=local \
    PUB_CACHE=/tmp/pub-cache DASH__SUPPRESS_ANALYTICS=true
COPY runner.py /opt/judge/runner.py
COPY umjunsik_cli.py /opt/judge/umjunsik_cli.py
COPY vendor/umjunsik/ /opt/judge/vendor/umjunsik/
COPY function/ /opt/judge/function/
COPY csharp/ /opt/csharp/
USER 65534:65534
WORKDIR /work
ENTRYPOINT ["python3", "-I", "/opt/judge/runner.py"]
