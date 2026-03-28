# Prerequisites

Before deploying the infrastructure, you need to set up the following AWS resources and configurations.

## AWS Profile

All AWS operations use the `AWS_PROFILE` environment variable to specify credentials.

Prefix each command with the profile:

```bash
AWS_PROFILE=conao3.k8s <command>
```

## Required AWS Resources

### 1. EC2 Key Pair

Create an EC2 key pair for SSH access to instances:

```bash
aws ec2 create-key-pair \
  --key-name dev-k8s-keypair \
  --query 'KeyMaterial' \
  --output text \
  --profile conao3.k8s > ~/.ssh/dev-k8s-keypair.pem

chmod 400 ~/.ssh/dev-k8s-keypair.pem
```

### 2. S3 Bucket and VM Import Role

Deploy S3 bucket and VM Import role (required for custom AMI upload):

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy s3
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy vm-import
```

### 3. Secrets Manager Secret

Create a secret containing credentials used by various services:

```bash
cat <<EOF > /tmp/dev-k8s-secret.json
{"rds-postgres-password":"ChangeMe12345","sancode-cloudflare-api-token":"YOUR_TOKEN"}
EOF

aws secretsmanager create-secret \
  --name dev-k8s-secret \
  --secret-string file:///tmp/dev-k8s-secret.json \
  --profile conao3.k8s

rm /tmp/dev-k8s-secret.json
```

#### Required Keys

| Key | Description | Used By |
|-----|-------------|---------|
| `rds-postgres-password` | RDS PostgreSQL master password | rds stack |
| `sancode-cloudflare-api-token` | Cloudflare API token for Pages deploy | sancode-resources CodeBuild |

### 4. CodeBuild GitHub Source Credentials

Register a GitHub Personal Access Token for CodeBuild to clone private repositories:

```bash
aws codebuild import-source-credentials \
  --server-type GITHUB \
  --auth-type PERSONAL_ACCESS_TOKEN \
  --token "YOUR_GITHUB_PAT" \
  --profile conao3.k8s
```

The token needs `repo` scope. This is a per-region, per-account setting (not per-project).

#### Update Secret

To update the secret later:

```bash
aws secretsmanager get-secret-value \
  --secret-id dev-k8s-secret \
  --query SecretString \
  --output text \
  --profile conao3.k8s | jq . > /tmp/dev-k8s-secret.json

$EDITOR /tmp/dev-k8s-secret.json

aws secretsmanager update-secret \
  --secret-id dev-k8s-secret \
  --secret-string file:///tmp/dev-k8s-secret.json \
  --profile conao3.k8s

rm /tmp/dev-k8s-secret.json
```

## Required Tools

### Local Development

- **Clojure**: For deploying infrastructure
- **AWS CLI**: For AWS operations
- **SAM CLI**: For CloudFormation deployment
- **kubectl**: For Kubernetes operations
- **kind** (optional): For local testing

### Installation

#### macOS (Homebrew)

```bash
brew install clojure/tools/clojure
brew install awscli
brew install aws-sam-cli
brew install kubectl
brew install kind
```

#### NixOS / Nix

```bash
nix-shell -p clojure awscli2 aws-sam-cli kubectl kind
```

#### Linux (Manual)

Follow official installation guides:
- [Clojure](https://clojure.org/guides/install_clojure)
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- [SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation)

## AWS Permissions

The AWS profile needs the following permissions:

- EC2 (full access for compute resources)
- VPC (full access for networking)
- ELB (full access for ALB)
- CloudFormation (full access for stack management)
- S3 (full access for VM Import bucket)
- IAM (limited access for role creation)
- Secrets Manager (read access)
- SSM Parameter Store (read/write for AMI ID)
- EFS (full access)
- AutoScaling (full access)

## Root Account Resources

Root account resources (Budget, Cost Anomaly Detection, SNS, Chatbot) are managed separately using the `aws-infra-root` module.

These resources use the `conao3.root` AWS profile and are deployed to:
- **ap-northeast-1**: SNS Topic and IAM Role
- **us-east-1**: AWS Budget, Cost Anomaly Detection, Chatbot

See [003-deployment.md](003-deployment.md#root-account-resources) for deployment instructions.

## Verification

Verify your setup:

```bash
# Check AWS profile
aws sts get-caller-identity --profile conao3.k8s

# Check Clojure
clojure --version

# Check kubectl
kubectl version --client

# Check key pair
ls -l ~/.ssh/dev-k8s-keypair.pem
```

All commands should succeed before proceeding to deployment.
