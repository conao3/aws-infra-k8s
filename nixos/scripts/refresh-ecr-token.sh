set -euo pipefail

log() {
  echo "[$(date -Iseconds)] $*" >&2
}

get_imds_token() {
  local token=""
  for i in {1..3}; do
    token=$(curl -s -X PUT "http://[fd00:ec2::254]/latest/api/token" \
      -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null) && break
    log "Retry $i: Failed to get IMDS token"
    sleep 5
  done

  if [ -z "${token}" ]; then
    log "ERROR: Failed to get IMDS token after 3 retries"
    exit 1
  fi

  echo "${token}"
}

TOKEN=$(get_imds_token)

REGION=$(curl -s -H "X-aws-ec2-metadata-token: ${TOKEN}" \
  "http://[fd00:ec2::254]/latest/meta-data/placement/region")

if [ -z "${REGION}" ]; then
  log "ERROR: Failed to get AWS region from IMDS"
  exit 1
fi

ACCOUNT_ID=$(curl -s -H "X-aws-ec2-metadata-token: ${TOKEN}" \
  "http://[fd00:ec2::254]/latest/dynamic/instance-identity/document" | \
  grep accountId | cut -d'"' -f4)

if [ -z "${ACCOUNT_ID}" ]; then
  log "ERROR: Failed to get AWS account ID from IMDS"
  exit 1
fi

ECR_ENDPOINT="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

ECR_PASSWORD=$(aws ecr get-login-password --region "${REGION}" 2>&1)
if [ $? -ne 0 ]; then
  log "ERROR: Failed to get ECR login password: ${ECR_PASSWORD}"
  exit 1
fi

TEMP_FILE=$(mktemp)
trap 'rm -f "${TEMP_FILE}"' EXIT

cat > "${TEMP_FILE}" << EOF
mirrors:
  "${ECR_ENDPOINT}":
    endpoint:
      - "https://${ECR_ENDPOINT}"
configs:
  "${ECR_ENDPOINT}":
    auth:
      username: AWS
      password: "${ECR_PASSWORD}"
EOF

mkdir -p /etc/rancher/k3s
mv "${TEMP_FILE}" /etc/rancher/k3s/registries.yaml

log "ECR credentials refreshed for ${ECR_ENDPOINT}"
