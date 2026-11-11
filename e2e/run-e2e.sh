#!/bin/bash
#
# End-to-end test: builds the ping/pong/router lambdas against the freshly-built
# edd-core, deploys self-contained AWS infra (API Gateway + S3 + DynamoDB + SQS),
# then exercises the real CQRS flow:
#   1. POST a :ping command via API Gateway.
#   2. ping --effect--> router --> pong --effect--> router --> ping ... (hop-guarded).
#   3. Upload a file to the import S3 bucket and verify the bucket-filter command.
# run-e2e-local.sh runs the same e2e/it suite on Undertow + Kafka + Postgres.
#
# Designed to be idempotent: every resource is a CloudFormation stack, deployed
# (created or updated) in place. Re-running updates rather than recreates.

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${BLUE}[E2E]${NC} $1"; }
ok()   { echo -e "${GREEN}[E2E OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[E2E WARN]${NC} $1"; }
err()  { echo -e "${RED}[E2E ERR]${NC} $1"; }

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${E2E_DIR}/.." && pwd)"
CF="${E2E_DIR}/cloudformation"

# The E2E runs under its OWN isolated environment name (NOT the build's
# EnvironmentNameLower) so its resources never collide with real infra in the
# account -- in particular the bucket names edd-core hardcodes from the env:
# {account}-{env}-sqs (oversized SQS bodies) and {account}-{env}-aggregates (view store).
# Override with E2E_ENV if needed.
ENV="${E2E_ENV:-cqrs-e2e}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-1}}"
ACCOUNT="${AccountId:-${TARGET_ACCOUNT_ID:-}}"
BUILD_TAG="${BUILD_ID:-local}"
REALM="test"

if [ -z "${ACCOUNT}" ]; then
  ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
fi

log "env=${ENV} region=${REGION} account=${ACCOUNT} build=${BUILD_TAG}"

# Reliability guard: never race a concurrent run on the shared ${ENV}-e2e-* stacks.
# A second run colliding mid-deploy is what previously produced stale deploys, so
# fail fast and clearly instead. (Serialize the E2E stage, or set a distinct
# E2E_ENV per executor, if builds can run in parallel.)
in_progress="$(aws cloudformation list-stacks --region "${REGION}" \
  --stack-status-filter CREATE_IN_PROGRESS UPDATE_IN_PROGRESS \
                        UPDATE_ROLLBACK_IN_PROGRESS ROLLBACK_IN_PROGRESS REVIEW_IN_PROGRESS \
  --query "StackSummaries[?starts_with(StackName, '${ENV}-e2e')].StackName" \
  --output text 2>/dev/null || true)"
if [ -n "${in_progress}" ]; then
  err "Another E2E run appears to be in progress (stacks: ${in_progress}). Aborting to avoid a corrupt/stale deploy."
  exit 1
fi

deploy() {
  local stack="$1"; local template="$2"; shift 2
  log "Deploying stack ${stack}"
  aws cloudformation deploy \
    --region "${REGION}" \
    --stack-name "${stack}" \
    --template-file "${template}" \
    --capabilities CAPABILITY_NAMED_IAM \
    --no-fail-on-empty-changeset \
    --parameter-overrides "$@"
}

output() {
  local stack="$1"; local key="$2"
  aws cloudformation describe-stacks --region "${REGION}" --stack-name "${stack}" \
    --query "Stacks[0].Outputs[?OutputKey=='${key}'].OutputValue" --output text
}

# Reliability guard: prove the deployed BUILD alias is running EXACTLY the jar we
# just built. Lambda's CodeSha256 is base64(sha256(package)); compare it to the
# local jar. Catches any stale-deploy / cache / no-op-update situation loudly.
verify_deployed_code() {
  local fn="$1"; local jar="$2"
  local expected actual
  expected="$(openssl dgst -sha256 -binary "${jar}" | base64)"
  actual="$(aws lambda get-function-configuration --region "${REGION}" \
            --function-name "${fn}:BUILD" --query CodeSha256 --output text)"
  if [ "${expected}" != "${actual}" ]; then
    err "Deployed code for ${fn} does NOT match the built jar (expected ${expected}, got ${actual}). Stale deploy?"
    exit 1
  fi
  ok "Verified ${fn}:BUILD runs the freshly built jar"
}

uuid() { cat /proc/sys/kernel/random/uuid; }

# Extract a numeric :hops from an arbitrarily-nested JSON response.
extract_hops() {
  jq -r '[.. | objects | .hops? // empty] | first // empty' 2>/dev/null || true
}

extract_last() {
  jq -r '[.. | objects | .last? // empty] | first // empty' 2>/dev/null || true
}

# 1. DynamoDB stores (existing edd-core template) for each service.
for svc in ping-svc pong-svc; do
  deploy "${ENV}-e2e-ddb-${svc}" "${ROOT_DIR}/dynamodb/files/db.yaml" \
    "EnvironmentNameLower=${ENV}" "DatabaseName=${svc}" "Realm=${REALM}"
done

# 2. Shared infra: buckets + import queue.
deploy "${ENV}-e2e-infra" "${CF}/infra.yaml" \
  "EnvironmentNameLower=${ENV}" "ImportResourceName=ping-svc"

DEPLOY_BUCKET="$(output "${ENV}-e2e-infra" DeploymentBucketName)"
IMPORT_QUEUE_ARN="$(output "${ENV}-e2e-infra" ImportQueueArn)"
IMPORT_BUCKET="$(output "${ENV}-e2e-infra" ImportBucketName)"
log "deployment-bucket=${DEPLOY_BUCKET} import-bucket=${IMPORT_BUCKET}"

# 3. Render ping-svc from the edd-core-scaffold template, then overlay the e2e
#    domain (ping.clj), the no-auth filters and the uberjar build. Running the
#    generated unit tests here proves the template itself still works.
declare -A SVC_DIR=([ping-svc]="target/ping-svc" [pong-svc]="pong-svc" [router-svc]="router-svc")
PING_DIR="${E2E_DIR}/${SVC_DIR[ping-svc]}"

log "Rendering ping-svc from the edd-core-scaffold template into ${PING_DIR}"
rm -rf "${PING_DIR}"
( cd "${ROOT_DIR}/modules/edd-core-scaffold" \
  && clojure -M -m edd.scaffold ping-svc \
       --dir "${PING_DIR}" \
       --edd-core-root "${ROOT_DIR}" \
       --runtime lambda --distributor sqs \
       --event-store dynamodb --view-store s3 \
       --deploy none </dev/null )
cp -r "${E2E_DIR}/ping-svc/." "${PING_DIR}/"
( cd "${PING_DIR}" && clojure -M:test:unit </dev/null )
ok "Rendered ping-svc passes its generated tests"

# 4. Build the three lambda uberjars against local edd-core.
BUILD_ALIAS='{:aliases {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.14"}} :ns-default build}}}'

build_uber() {
  local dir="$1"; local main="$2"; local artifact="$3"
  log "Building uberjar ${artifact} (main ${main})"
  # The runtime/start ctx (incl. dynamodb event-store/register -> lambda.ctx/init)
  # is evaluated during AOT compilation, so the env vars its schema validates must
  # be present at build time. The real values are supplied by the Lambda at runtime.
  # Dummy AWS creds keep aws.ctx/resolve-credentials from stalling on IMDS.
  ( cd "${E2E_DIR}/${dir}" \
    && ResourceName="${artifact}" \
       ServiceName="${artifact}" \
       PublicHostedZoneName="example.com" \
       EnvironmentNameLower="${ENV}" \
       AccountId="${ACCOUNT}" \
       Region="${REGION}" \
       AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-dummy}" \
       AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-dummy}" \
       clojure -Sdeps "${BUILD_ALIAS}" -T:build uber :main "${main}" :artifact "\"${artifact}\"" )
}
build_uber "${SVC_DIR[ping-svc]}"   ping-svc.main ping-svc
build_uber "${SVC_DIR[pong-svc]}"   pong.main     pong-svc
build_uber "${SVC_DIR[router-svc]}" router.main   router-svc

# 5. Upload artifacts under a timestamped, build-tagged key. A unique key per
#    run means CloudFormation always sees a new artifact and republishes the
#    BUILD alias -- no reliance on jar-hash determinism, no stale/cached version.
#    (The CodeSha256 check after each deploy proves the alias serves this jar.)
RUN_TAG="${BUILD_TAG}-$(date +%Y%m%d%H%M%S)"
declare -A S3KEY
for svc in ping-svc pong-svc router-svc; do
  key="${svc}-${RUN_TAG}.jar"
  S3KEY[$svc]="${key}"
  log "Uploading ${svc}.jar -> s3://${DEPLOY_BUCKET}/${key}"
  aws s3 cp "${E2E_DIR}/${SVC_DIR[$svc]}/target/${svc}.jar" "s3://${DEPLOY_BUCKET}/${key}" --region "${REGION}"
done

# 6. API Gateway first, so its URL/id can be injected into the service lambdas
#    (cross-service remote dependencies POST to {ApiUrl}/private/{stage}/{svc}/query).
deploy "${ENV}-e2e-api" "${CF}/api.yaml" \
  "EnvironmentNameLower=${ENV}" "Region=${REGION}"

API_URL="$(output "${ENV}-e2e-api" ApiUrl)"
API_ID="$(output "${ENV}-e2e-api" ApiId)"
# AWS::ApiGateway::Deployment does not redeploy when the RestApi body changes,
# so the stage can serve a stale definition. Force a fresh deployment.
aws apigateway create-deployment --region "${REGION}" \
  --rest-api-id "${API_ID}" --stage-name e2e >/dev/null
ok "API deployed at ${API_URL} (id ${API_ID})"

# 7. Router lambda (creates the {env}-router-svc-response queue + mapping).
deploy "${ENV}-e2e-router" "${CF}/lambda-router.yaml" \
  "EnvironmentNameLower=${ENV}" "Region=${REGION}" \
  "DeploymentBucketName=${DEPLOY_BUCKET}" "S3Key=${S3KEY[router-svc]}"
verify_deployed_code "${ENV}-e2e-router-svc" "${E2E_DIR}/${SVC_DIR[router-svc]}/target/router-svc.jar"

# 8. ping + pong lambdas (ping also consumes the import queue). Both get the
#    ApiUrl (for remote deps) and CQRSApiId (for the apigateway invoke grant).
deploy "${ENV}-e2e-ping" "${CF}/lambda-svc.yaml" \
  "EnvironmentNameLower=${ENV}" "ResourceName=ping-svc" "Region=${REGION}" \
  "DeploymentBucketName=${DEPLOY_BUCKET}" "S3Key=${S3KEY[ping-svc]}" \
  "EnableImport=true" "ImportQueueArn=${IMPORT_QUEUE_ARN}" \
  "ApiUrl=${API_URL}" "CQRSApiId=${API_ID}"
verify_deployed_code "${ENV}-e2e-ping-svc" "${E2E_DIR}/${SVC_DIR[ping-svc]}/target/ping-svc.jar"

deploy "${ENV}-e2e-pong" "${CF}/lambda-svc.yaml" \
  "EnvironmentNameLower=${ENV}" "ResourceName=pong-svc" "Region=${REGION}" \
  "DeploymentBucketName=${DEPLOY_BUCKET}" "S3Key=${S3KEY[pong-svc]}" \
  "EnableImport=false" "ImportQueueArn=none" \
  "ApiUrl=${API_URL}" "CQRSApiId=${API_ID}"
verify_deployed_code "${ENV}-e2e-pong-svc" "${E2E_DIR}/${SVC_DIR[pong-svc]}/target/pong-svc.jar"

# 9. Upload a file to the import bucket (the infra action behind the S3 bucket
#    filter test). The object's aggregate id == request-id segment of the key.
#    The assertion itself lives in the Clojure integration test.
OBJ_ID="$(uuid)"
KEY="${REALM}/$(date +%F)/$(uuid)/${OBJ_ID}.json"
log "Uploading import object s3://${IMPORT_BUCKET}/${KEY}"
echo '{"e2e":"object-upload"}' >/tmp/e2e-object.json
aws s3 cp /tmp/e2e-object.json "s3://${IMPORT_BUCKET}/${KEY}" --region "${REGION}"

# 10. Run the Clojure integration tests against the deployed stack.
log "Running Clojure integration tests (e2e/it) against ${API_URL}"
#     The fan-out tests use these request ids so step 11 finds their responses
#     in the response cache bucket.
CACHED_REQUEST_ID="$(uuid)"
INLINE_REQUEST_ID="$(uuid)"
( cd "${E2E_DIR}/it" \
  && ApiUrl="${API_URL}" \
     E2E_IMPORT_OBJECT_ID="${OBJ_ID}" \
     E2E_CACHED_REQUEST_ID="${CACHED_REQUEST_ID}" \
     E2E_INLINE_REQUEST_ID="${INLINE_REQUEST_ID}" \
     clojure -M:it --skip-meta :local )

# 11. The response cache in S3: pong-svc (cache, 3 effects per part) stored its
#     7 effect fan-out in 3 parts, the events with the first one; ping-svc (no
#     cache) stored nothing.
CACHE_BUCKET="${ACCOUNT}-${ENV}-sqs"

cached_keys() {
  aws s3api list-objects-v2 --bucket "${CACHE_BUCKET}" --prefix "response/$1/" \
    --query 'Contents[].Key' --output json --region "${REGION}" | jq -r '(. // []) | sort | .[]'
}

cached_counts() {
  aws s3 cp "s3://${CACHE_BUCKET}/$1" - --region "${REGION}" \
    | jq -c '[(.resp.effects | length), (.resp.events | length)]'
}

EXPECTED_KEYS="$(printf 'response/%s/0/pong-svc-part.%s.json\n' \
  "${CACHED_REQUEST_ID}" 0 "${CACHED_REQUEST_ID}" 1 "${CACHED_REQUEST_ID}" 2)"
ACTUAL_KEYS="$(cached_keys "${CACHED_REQUEST_ID}")"
if [ "${ACTUAL_KEYS}" != "${EXPECTED_KEYS}" ]; then
  err "pong-svc response cache: expected 3 parts in s3://${CACHE_BUCKET}, found: ${ACTUAL_KEYS:-none}"
  exit 1
fi

ACTUAL_COUNTS="$(for key in ${ACTUAL_KEYS}; do cached_counts "${key}"; done | tr '\n' ' ')"
if [ "${ACTUAL_COUNTS}" != "[3,1] [3,0] [1,0] " ]; then
  err "pong-svc response cache: expected [effects,events] per part [3,1] [3,0] [1,0], found: ${ACTUAL_COUNTS}"
  exit 1
fi
ok "pong-svc cached its response in 3 S3 parts: ${ACTUAL_COUNTS}"

INLINE_KEYS="$(cached_keys "${INLINE_REQUEST_ID}")"
if [ -n "${INLINE_KEYS}" ]; then
  err "ping-svc has no response cache but stored: ${INLINE_KEYS}"
  exit 1
fi
ok "ping-svc sent its response inline, nothing in S3"

ok "All E2E tests passed."
