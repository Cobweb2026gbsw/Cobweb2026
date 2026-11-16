# syntax=docker/dockerfile:1
# Build from the repository root for linux/amd64. The judge archive is built from
# the trusted judge/ Dockerfile before this image is assembled.
FROM docker:29.8.2-dind

RUN apk add --no-cache openjdk21-jre-headless busybox-extras \
    && java -version \
    && busybox-extras --list | grep -qx httpd

WORKDIR /opt/cobweb

# Keep each language archive slice in its own OCI layer. The source archive is
# mounted during the build and stays out of the image. Eight 192 MiB slices
# leave room for compiler updates without another oversized registry upload.
ARG LANGUAGE_ARCHIVE=/archive/cobweb-languages-amd64.tar.gz
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    if [ ! -s "$LANGUAGE_ARCHIVE" ] || [ "$(wc -c < "$LANGUAGE_ARCHIVE")" -gt 1610612736 ]; then \
        echo 'The language archive must be nonempty and fit in the eight 192 MiB slices.' >&2; exit 1; \
    fi \
    && mkdir -p judge-image \
    && dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-00 bs=1M count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-01 bs=1M skip=192 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-02 bs=1M skip=384 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-03 bs=1M skip=576 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-04 bs=1M skip=768 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-05 bs=1M skip=960 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-06 bs=1M skip=1152 count=192 status=none
RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    dd if="$LANGUAGE_ARCHIVE" of=judge-image/part-07 bs=1M skip=1344 count=192 status=none

RUN --mount=type=bind,source=build/cloudflare/judge,target=/archive \
    set -o pipefail \
    && expected="$(sha256sum < "$LANGUAGE_ARCHIVE")" \
    && actual="$(cat judge-image/part-* | sha256sum)" \
    && test "$actual" = "$expected"

COPY build/libs/cobweb2026-*-SNAPSHOT.jar app.jar
COPY --chmod=0644 deploy/cloudflare/judge/check-sandbox.sh check-sandbox.sh
COPY --chmod=0755 deploy/cloudflare/judge/entrypoint.sh entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-init", "--", "/opt/cobweb/entrypoint.sh"]
