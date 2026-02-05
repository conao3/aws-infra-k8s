# Deployment

This guide covers deploying the AWS infrastructure and Kubernetes applications.

## Prerequisites

Before deploying, ensure you have completed all steps in [002-prerequisites.md](002-prerequisites.md).

## Deploy All Infrastructure

Deploy all infrastructure components:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

This deploys:
- **ap-northeast-1**: Network, ALB, Cluster (k3s), EFS, etc.
- **us-east-1**: CloudFront + WAF (automatically deployed to us-east-1)

**CloudFront Free Plan Limits:**
- 1M requests/month
- 100GB data transfer/month
- 5 WAF rules max (currently using 3)
- No overage charges (performance throttling instead)

## Deploy Individual Modules

### Available Modules

**Infrastructure (ap-northeast-1):**
- `s3` - S3 bucket for VM Import
- `vm-import` - VM Import IAM role for custom AMI upload
- `network` - VPC, Subnets, Internet Gateway, NAT Gateway
- `routing` - Route Tables and Route Table Associations
- `security-group` - Security Groups for ALB and EC2
- `cluster` - k3s cluster on NixOS
- `alb` - Application Load Balancer with Target Group
- `eice` - EC2 Instance Connect Endpoint
- `github-oidc` - GitHub Actions OIDC provider and IAM role
- `rds` - RDS PostgreSQL database
- `cognito` - Cognito User Pool
- `efs` - EFS One Zone for persistent storage
- `ssh-tunnel` - SSH tunnel instance (not included in `deploy all`)
- `ami-builder` - AMI builder instance (not included in `deploy all`)

**Global Resources (us-east-1):**
- `cloudfront` - CloudFront Distribution + WAF WebACL

### Deploy Single Module

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

**Example**: Deploy only the routing module

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```

### Module Dependencies

Modules must be deployed in the following order:

1. `s3`, `vm-import` (prerequisites)
2. `network`
3. `routing`
4. `security-group`
5. `cluster`, `eice`, `efs`
6. `alb`
7. `cloudfront`

The `deploy all` command handles dependencies automatically.

## Root Account Resources

Root account resources (Budget, Cost Anomaly Detection, SNS, Chatbot) are managed separately using the `aws-infra-root` module.

### Deploy All Root Resources

```bash
clojure -M -m conao3.aws-infra-root deploy all
```

This deploys:
- **ap-northeast-1**: SNS Topic for cost alerts, IAM Role for Chatbot
- **us-east-1**: AWS Budget, Cost Anomaly Monitor/Subscription, Chatbot Slack Configuration

### Deploy Individual Root Module

Available root modules:
- `sns` - SNS Topic and IAM Role in ap-northeast-1
- `budget` - AWS Budget and Cost Anomaly Detection in us-east-1
- `chatbot` - Chatbot Slack Configuration in us-east-1

```bash
clojure -M -m conao3.aws-infra-root deploy <module-name>
```

**Examples**:

```bash
clojure -M -m conao3.aws-infra-root deploy sns
clojure -M -m conao3.aws-infra-root deploy budget
clojure -M -m conao3.aws-infra-root deploy chatbot \
  --slack-workspace-id T123456 \
  --slack-channel-id C123456
```

All root modules automatically use the `conao3.root` AWS profile.

## Deploy Kubernetes Applications

After the infrastructure is deployed, deploy Kubernetes applications:

```bash
AWS_PROFILE=conao3.k8s bin/k8s deploy
```

This deploys:
- Traefik Ingress Controller
- app1, app2, app3 (sample applications)
- Kubernetes Dashboard
- Monitoring stack (Prometheus, Grafana, Node Exporter, kube-state-metrics)
- AWS EFS CSI Driver

See [006-kubernetes-apps.md](006-kubernetes-apps.md) for details on each application.

## Verification

### Infrastructure Verification

Check CloudFormation stacks:

```bash
AWS_PROFILE=conao3.k8s aws cloudformation list-stacks \
  --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
  --query 'StackSummaries[?starts_with(StackName, `dev-k8s-`)].StackName' \
  --output table
```

### Application Verification

Check Kubernetes pods:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -A'
```

### Access Verification

Get CloudFront URL:

```bash
AWS_PROFILE=conao3.k8s aws cloudformation describe-stacks \
  --stack-name dev-k8s-cloudfront \
  --region us-east-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`DistributionDomainName`].OutputValue' \
  --output text
```

Access the URL in a browser to verify the application is running.

## Troubleshooting

### Stack Creation Failed

Check CloudFormation events:

```bash
AWS_PROFILE=conao3.k8s aws cloudformation describe-stack-events \
  --stack-name dev-k8s-<module-name> \
  --max-items 10
```

### Application Not Accessible

1. Check ALB target health:

```bash
AWS_PROFILE=conao3.k8s aws elbv2 describe-target-health \
  --target-group-arn $(aws cloudformation list-exports \
    --query "Exports[?Name=='dev-k8s-TargetGroup'].Value" \
    --output text \
    --profile conao3.k8s)
```

2. Check Traefik logs:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login \
  'kubectl logs -n traefik -l app=traefik --tail=50'
```

3. Check pod status:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login \
  'kubectl get pods -A'
```

## Cleanup

To delete all resources (warning: this is irreversible):

```bash
# Delete Kubernetes applications first
AWS_PROFILE=conao3.k8s bin/ssh/node login \
  'kubectl delete --all deployments,services,ingresses -A'

# Delete infrastructure stacks (in reverse order)
AWS_PROFILE=conao3.k8s aws cloudformation delete-stack \
  --stack-name dev-k8s-cloudfront --region us-east-1

AWS_PROFILE=conao3.k8s aws cloudformation delete-stack \
  --stack-name dev-k8s-alb

AWS_PROFILE=conao3.k8s aws cloudformation delete-stack \
  --stack-name dev-k8s-cluster

# Continue with other stacks...
```

Or use the AWS Console to delete stacks manually.
