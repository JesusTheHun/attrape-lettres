#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Bundle, upload, deploy. Idempotent — run it as often as you like.
#
#   ./scripts/deploy.sh                       # deploy with whatever is set
#   STACK=attrape-staging ./scripts/deploy.sh # a second, disposable copy
#
# Everything is a variable with a default so the common case is no arguments,
# and every variable is echoed back before anything happens, because the two
# ways to get this wrong — the wrong account and the wrong region — both look
# like success and produce a second empty database.
# ---------------------------------------------------------------------------

STACK="${STACK:-attrape-api}"
# Paris. The data is French children's progress and French parents' analytics;
# keeping both in the EU is the default that needs no justification later.
REGION="${REGION:-eu-west-3}"
ALARM_EMAIL="${ALARM_EMAIL:-}"
DOMAIN_NAME="${DOMAIN_NAME:-}"
HOSTED_ZONE_ID="${HOSTED_ZONE_ID:-}"
TELEMETRY="${TELEMETRY:-1}"
QUIET_SYNC_ALARM="${QUIET_SYNC_ALARM:-false}"
# Set to 0 to stop the service dead. See the template.
MAX_CONCURRENCY="${MAX_CONCURRENCY:-20}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"

command -v aws >/dev/null || { echo "aws CLI not found" >&2; exit 1; }

account="$(aws sts get-caller-identity --query Account --output text)"
# The bucket CloudFormation stages the zip in. One per account and region, made
# once, reused forever.
artifacts="${ARTIFACTS_BUCKET:-${STACK}-artifacts-${account}-${REGION}}"

cat <<SUMMARY
stack      ${STACK}
account    ${account}
region     ${REGION}
artifacts  ${artifacts}
domain     ${DOMAIN_NAME:-<none — see the warning in README.md>}
telemetry  ${TELEMETRY}
alarms to  ${ALARM_EMAIL:-<nobody>}
max conc   ${MAX_CONCURRENCY}
SUMMARY

if [ "$MAX_CONCURRENCY" = "0" ]; then
  echo
  echo "MAX_CONCURRENCY=0 — this deploy TURNS THE SERVICE OFF."
  echo "Every request will be throttled. Both clients swallow that, so children"
  echo "keep playing offline and nobody sees an error; families simply stop"
  echo "syncing between devices until it is turned back on."
  printf 'type "off" to continue: '
  read -r confirm
  [ "$confirm" = "off" ] || { echo "aborted"; exit 1; }
fi

if ! aws s3api head-bucket --bucket "$artifacts" --region "$REGION" 2>/dev/null; then
  echo "creating artifacts bucket ${artifacts}"
  aws s3api create-bucket \
    --bucket "$artifacts" \
    --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=${REGION}" >/dev/null
  aws s3api put-public-access-block \
    --bucket "$artifacts" \
    --region "$REGION" \
    --public-access-block-configuration \
      "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
  # Old bundles are worth nothing the moment the next one lands, and a
  # never-expiring artifacts bucket is how an account quietly accumulates
  # gigabytes of dead zips.
  aws s3api put-bucket-lifecycle-configuration \
    --bucket "$artifacts" \
    --region "$REGION" \
    --lifecycle-configuration \
      '{"Rules":[{"ID":"expire-old-bundles","Status":"Enabled","Filter":{},"Expiration":{"Days":30}}]}'
fi

echo "==> tests"
# Not optional. Nothing downstream of a deploy will tell you it went wrong:
# both clients swallow every failure this service can produce.
pnpm test

echo "==> bundle"
pnpm bundle

echo "==> package"
packaged="$(mktemp -t attrape-template).yaml"
aws cloudformation package \
  --template-file infra/template.yaml \
  --s3-bucket "$artifacts" \
  --s3-prefix bundles \
  --region "$REGION" \
  --output-template-file "$packaged" >/dev/null

echo "==> deploy"
params=(
  "TelemetryEnabled=${TELEMETRY}"
  "EnableQuietSyncAlarm=${QUIET_SYNC_ALARM}"
  "MaxConcurrency=${MAX_CONCURRENCY}"
)
[ -n "$ALARM_EMAIL" ] && params+=("AlarmEmail=${ALARM_EMAIL}")
[ -n "$DOMAIN_NAME" ] && params+=("DomainName=${DOMAIN_NAME}")
[ -n "$HOSTED_ZONE_ID" ] && params+=("HostedZoneId=${HOSTED_ZONE_ID}")

aws cloudformation deploy \
  --template-file "$packaged" \
  --stack-name "$STACK" \
  --region "$REGION" \
  --capabilities CAPABILITY_IAM \
  --no-fail-on-empty-changeset \
  --parameter-overrides "${params[@]}"

rm -f "$packaged"

aws cloudformation describe-stacks \
  --stack-name "$STACK" \
  --region "$REGION" \
  --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" \
  --output table

endpoint="$(aws cloudformation describe-stacks \
  --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='ApiEndpoint'].OutputValue" --output text)"

echo "==> smoke"
# Not "did it deploy" — that is what the command above answered. This asks the
# one question the deploy cannot: does an ETag still survive the round trip.
# Everything in front of a Lambda that might strip or weaken that header breaks
# sync permanently and silently, so it gets checked on every single deploy.
id="smoke-$(date +%s)-$RANDOM"
body='{"children":[],"removed":{}}'

created="$(curl -fsS -o /dev/null -w '%{http_code} %header{etag}' \
  -X PUT "${endpoint}/household/${id}" \
  -H 'content-type: application/json' -d "$body")"
echo "create      ${created}"
[ "${created% *}" = "200" ] || { echo "FAILED: create did not answer 200" >&2; exit 1; }

etag="${created#* }"
[ -n "$etag" ] || { echo "FAILED: no ETag came back — sync would break for everyone" >&2; exit 1; }

# No `-f` here: it would turn the 412 we are hoping for into a shell failure.
conflict="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X PUT "${endpoint}/household/${id}" \
  -H 'content-type: application/json' -H 'if-match: "999"' -d "$body")"
echo "stale push  ${conflict}"
[ "$conflict" = "412" ] || {
  echo "FAILED: a stale If-Match answered ${conflict}, not 412." >&2
  echo "        Something in front of this service is eating If-Match." >&2
  exit 1
}

accepted="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X PUT "${endpoint}/household/${id}" \
  -H 'content-type: application/json' -H "if-match: ${etag}" -d "$body")"
echo "good push   ${accepted}"
[ "$accepted" = "200" ] || { echo "FAILED: a fresh If-Match answered ${accepted}" >&2; exit 1; }

# Tidy up after ourselves. The function's own role cannot delete anything — that
# is deliberate — but the person running a deploy can, and a smoke household per
# deploy would otherwise pile up forever next to real families.
table="$(aws cloudformation describe-stacks \
  --stack-name "$STACK" --region "$REGION" \
  --query "Stacks[0].Outputs[?OutputKey=='HouseholdTableName'].OutputValue" --output text)"
aws dynamodb delete-item \
  --table-name "$table" --region "$REGION" \
  --key "{\"pk\":{\"S\":\"${id}\"},\"sk\":{\"S\":\"#root\"}}" >/dev/null

echo "ETag round trip intact."
