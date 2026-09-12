#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
(cd frontend && npm ci)
./gradlew :server:bootJar
exec java -jar server/build/libs/server-0.1.0.jar
