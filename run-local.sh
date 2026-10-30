#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ -f .env.local ]]; then
  set -a
  source .env.local
  set +a
fi
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/.maven}"
./mvnw -q -Dmaven.repo.local="$MAVEN_USER_HOME/repository" -DskipTests package
exec java -jar target/bankProductCreation-0.0.1-SNAPSHOT.jar "$@"
