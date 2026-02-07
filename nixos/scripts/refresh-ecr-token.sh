#!/bin/bash
set -euo pipefail

# Get AWS region from instance metadata (IMDSv2)
TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
REGION=$(curl -s -H "X-aws-ec2-metadata-token: ${TOKEN}" "http://169.254.169.254/latest/meta-data/placement/region")
ACCOUNT_ID=$(curl -s -H "X-aws-ec2-metadata-token: ${TOKEN}" "http://169.254.169.254/latest/dynamic/instance-identity/document" | grep accountId | cut -d'"' -f4)

ECR_ENDPOINT="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

# Get ECR login password
ECR_PASSWORD=$(aws ecr get-login-password --region "${REGION}")

# Write k3s registries.yaml
mkdir -p /etc/rancher/k3s
cat > /etc/rancher/k3s/registries.yaml << EOF
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

echo "ECR credentials refreshed for ${ECR_ENDPOINT}"
