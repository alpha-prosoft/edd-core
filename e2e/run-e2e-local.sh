#!/bin/bash
#
# Local end-to-end test: the same ping/pong/router services and the same e2e/it
# suite as run-e2e.sh, on Undertow + Kafka + Postgres in docker compose (profile
# e2e) instead of Lambda + SQS + DynamoDB + S3. Postgres and Kafka come from the
# root docker-compose.yaml, migrated by pre-build.sh.
#
#   run-e2e-local.sh build   render ping-svc, build the three jars into e2e/target/local
#   run-e2e-local.sh up      start the services and the gateway, wait until they answer
#   run-e2e-local.sh test    run e2e/it against the gateway
#   run-e2e-local.sh down    stop the services
#   run-e2e-local.sh         all of the above
#
# build runs in a clojure container when the clojure CLI is not installed
# (or E2E_BUILD_IN_DOCKER=true).

set -euo pipefail

BLUE='\033[0;34m'; GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
log() { echo -e "${BLUE}[E2E-LOCAL]${NC} $1"; }
ok()  { echo -e "${GREEN}[E2E-LOCAL OK]${NC} $1"; }
err() { echo -e "${RED}[E2E-LOCAL ERR]${NC} $1"; }

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${E2E_DIR}/.." && pwd)"
JARS_DIR="${E2E_DIR}/target/local"
PING_DIR="${E2E_DIR}/target/ping-svc-local"
API_URL="${E2E_LOCAL_API_URL:-http://127.0.0.1:8085}"
ENV_NAME="cqrs-e2e"
SERVICES=(ping-svc pong-svc router-svc gateway)
CLOJURE_IMAGE="${DOCKER_URL:-}clojure:temurin-21-tools-deps-1.12.6.1673-bookworm-slim"
BUILD_ALIAS='{:aliases {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.14"}} :ns-default build}}}'

compose() {
  ( cd "${ROOT_DIR}" && docker compose --profile e2e "$@" )
}

uber() {
  local dir="$1"; local main="$2"; local artifact="$3"
  log "Building ${artifact} (main ${main})"
  ( cd "${dir}" && clojure -Sdeps "${BUILD_ALIAS}" -T:build uber :main "${main}" :artifact "\"${artifact}-local\"" )
  cp "${dir}/target/${artifact}-local.jar" "${JARS_DIR}/${artifact}.jar"
}

build() {
  if [ "${E2E_BUILD_IN_DOCKER:-false}" = "true" ] || ! command -v clojure >/dev/null; then
    log "Building in ${CLOJURE_IMAGE}"
    mkdir -p "${HOME}/.m2" "${HOME}/.gitlibs"
    docker run --rm --network host \
      --user "$(id -u):$(id -g)" -e HOME=/tmp/home -e E2E_BUILD_IN_DOCKER=false \
      --tmpfs "/tmp/home:exec,uid=$(id -u),gid=$(id -g)" \
      -v "${ROOT_DIR}:${ROOT_DIR}" -w "${ROOT_DIR}" \
      -v "${HOME}/.m2:/tmp/home/.m2" -v "${HOME}/.gitlibs:/tmp/home/.gitlibs" \
      "${CLOJURE_IMAGE}" bash e2e/run-e2e-local.sh build
    return
  fi

  log "Rendering ping-svc (undertow, kafka, postgres) into ${PING_DIR}"
  rm -rf "${PING_DIR}" "${JARS_DIR}"
  mkdir -p "${JARS_DIR}"
  ( cd "${ROOT_DIR}/modules/edd-core-scaffold" \
    && clojure -M -m edd.scaffold ping-svc \
         --dir "${PING_DIR}" \
         --edd-core-root "${ROOT_DIR}" \
         --runtime undertow --distributor kafka \
         --event-store postgres --view-store postgres \
         --deploy none </dev/null )
  cp -r "${E2E_DIR}/ping-svc/." "${PING_DIR}/"
  ( cd "${PING_DIR}" && clojure -M:test:unit </dev/null )
  ok "Rendered ping-svc passes its generated tests"

  uber "${PING_DIR}" ping-svc.main ping-svc
  uber "${E2E_DIR}/pong-svc" pong.local pong-svc
  uber "${E2E_DIR}/router-svc" router.local router-svc
  ok "Jars in ${JARS_DIR}"
}

create-topics() {
  local topics=("${ENV_NAME}.router")
  for svc in ping-svc pong-svc; do
    topics+=("${ENV_NAME}.test.${svc}.commands" "${ENV_NAME}.test.${svc}.events")
  done
  for topic in "${topics[@]}"; do
    compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 \
      --create --if-not-exists --topic "${topic}" --partitions 1 --replication-factor 1 >/dev/null
  done
}

query-status() {
  local svc="$1"
  curl -s -o /dev/null -w "%{http_code}" -m 5 -X POST "${API_URL}/${svc}/query" \
    -H "Content-Type: application/json" \
    -d "{\"request-id\":\"#$(cat /proc/sys/kernel/random/uuid)\",\"interaction-id\":\"#$(cat /proc/sys/kernel/random/uuid)\",\"meta\":{\"realm\":\":test\"},\"user\":{\"id\":\"e2e\",\"role\":\":e2e-tester\"},\"query\":{\"query-id\":\":get-by-id\",\"id\":\"#$(cat /proc/sys/kernel/random/uuid)\"}}" \
    || true
}

up() {
  [ -f "${JARS_DIR}/ping-svc.jar" ] || { err "No jars in ${JARS_DIR}, run build first"; exit 1; }
  create-topics
  compose up -d --force-recreate "${SERVICES[@]}"
  for svc in ping-svc pong-svc; do
    log "Waiting for ${svc}"
    local n=0
    until [ "$(query-status "${svc}")" = "200" ]; do
      n=$((n + 1))
      if [ "${n}" -gt 60 ]; then
        err "${svc} did not answer"
        compose logs --tail 80 "${svc}"
        exit 1
      fi
      sleep 2
    done
  done
  ok "Services answer at ${API_URL}"
}

run-tests() {
  log "Running e2e/it against ${API_URL}"
  if ! ( cd "${E2E_DIR}/it" && ApiUrl="${API_URL}" clojure -M:it --skip-meta :aws ); then
    compose logs --tail 200 ping-svc pong-svc router-svc || true
    exit 1
  fi
  ok "All local E2E tests passed"
}

down() {
  compose rm -sf "${SERVICES[@]}"
}

case "${1:-all}" in
  build) build ;;
  up) up ;;
  test) run-tests ;;
  down) down ;;
  all)
    build
    trap down EXIT
    up
    run-tests
    ;;
  *) err "Unknown command $1"; exit 1 ;;
esac
