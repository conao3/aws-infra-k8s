# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular, cost-optimized approach to deploying Kubernetes infrastructure on AWS. Built on k3s with NixOS, it delivers a secure, production-ready platform with CloudFront CDN, WAF protection, and integrated monitoring.

### Key Features

- **Cost-Optimized**: ~$31/month for a complete k8s cluster with monitoring
- **Secure by Default**: CloudFront WAF, DDoS protection, private subnets, EICE
- **Production-Ready**: Multi-app hosting with subdomain routing via Traefik
- **Fully Declarative**: Infrastructure as Code with Clojure + CloudFormation, NixOS for system config
- **Complete Monitoring**: Prometheus, Grafana, Node Exporter, kube-state-metrics
- **Easy to Use**: Simple CLI commands for deployment and management

### Architecture

```
Internet
  ↓ HTTPS
CloudFront (CDN + WAF Free Plan)
  ↓ HTTP (CloudFront Prefix List only)
Application Load Balancer
  ↓ NodePort 30080
Traefik Ingress Controller
  ↓ Host-based Routing
Kubernetes Services (app1, app2, app3, ...)
  ↓
k3s Cluster on NixOS (EC2)
```

**Security Features**:
- CloudFront accessible only from Cloudflare IPs (IP restriction)
- CloudFront WAF with 4 rules (Cloudflare IP Only, Rate Limit, Geo Block, SQLi Protection)
- ALB accessible only from CloudFront (Managed Prefix List)
- No public IPs on EC2 instances (access via EICE)
- DDoS protection enabled by default
- HTTPS enforced at CloudFront edge

**Cost Breakdown** (~$31/month):
- EC2 t4g.small: ~$12
- ALB: ~$18
- EBS 10GB: ~$1
- CloudFront & WAF: $0 (Free Plan)
- EFS: ~$0.19/GB

## Quick Start

### Prerequisites

1. **AWS Profile**: Set up AWS credentials and use `AWS_PROFILE=conao3.k8s` before each command

2. **Required Tools**: Clojure, AWS CLI, SAM CLI, kubectl

3. **Initial Setup**:
   ```bash
   # Create EC2 key pair
   aws ec2 create-key-pair --key-name dev-k8s-keypair \
     --query 'KeyMaterial' --output text > ~/.ssh/dev-k8s-keypair.pem
   chmod 400 ~/.ssh/dev-k8s-keypair.pem

   # Deploy S3 bucket and VM Import role
   clojure -M -m conao3.aws-infra-k8s deploy s3
   clojure -M -m conao3.aws-infra-k8s deploy vm-import
   ```

See [docs/002-prerequisites.md](docs/002-prerequisites.md) for full setup instructions.

### Deploy Infrastructure

```bash
# Deploy all infrastructure (VPC, ALB, k3s cluster, CloudFront, etc.)
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

### Deploy Applications

```bash
# Deploy Kubernetes applications (Traefik, apps, monitoring)
AWS_PROFILE=conao3.k8s bin/k8s deploy
```

### Access Your Cluster

```bash
# Get CloudFront URL
AWS_PROFILE=conao3.k8s aws cloudformation describe-stacks \
  --stack-name dev-k8s-cloudfront --region us-east-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`DistributionDomainName`].OutputValue' \
  --output text

# Access applications: https://xxx.cloudfront.net/

# Port forward Grafana
AWS_PROFILE=conao3.k8s bin/ssh/node grafana
# Open: http://localhost:30300 (admin/admin)

# Port forward Prometheus
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus
# Open: http://localhost:30900
```

## Documentation

### Getting Started

- [001-architecture.md](docs/001-architecture.md) - System architecture and design
- [002-prerequisites.md](docs/002-prerequisites.md) - Required tools and initial setup
- [003-deployment.md](docs/003-deployment.md) - Deployment procedures

### Advanced Topics

- [004-nixos-ami.md](docs/004-nixos-ami.md) - Custom NixOS AMI building
- [005-persistent-storage.md](docs/005-persistent-storage.md) - EFS persistent storage
- [006-kubernetes-apps.md](docs/006-kubernetes-apps.md) - Kubernetes applications
- [007-ingress-subdomain.md](docs/007-ingress-subdomain.md) - Multi-subdomain setup
- [008-monitoring.md](docs/008-monitoring.md) - Prometheus and Grafana
- [009-ssh-access.md](docs/009-ssh-access.md) - SSH access via EICE
- [010-kustomization.md](docs/010-kustomization.md) - Kustomization-based deployment

## Deployed Applications

| Application | Purpose | Access |
|-------------|---------|--------|
| **app1, app2, app3** | Sample static HTML apps | Via CloudFront (subdomain routing) |
| **Traefik** | Ingress Controller | Dashboard: NodePort 30081 |
| **Prometheus** | Metrics collection | NodePort 30900 |
| **Grafana** | Metrics visualization | NodePort 30300 (admin/admin) |
| **Kubernetes Dashboard** | Cluster management UI | NodePort 31353 (HTTPS) |
| **Node Exporter** | Host metrics | Internal (scraped by Prometheus) |
| **kube-state-metrics** | K8s object metrics | Internal (scraped by Prometheus) |

## Local Development

Test Kubernetes manifests locally with kind:

```bash
# Create local cluster
bin/k8s-local up

# Deploy applications
bin/k8s-local deploy

# Access applications
# - Traefik Dashboard: http://localhost:30081
# - Prometheus: http://localhost:30900
# - Grafana: http://localhost:30300
# - Dashboard: https://localhost:31353

# Test subdomain routing
echo "127.0.0.1 app1.example.local app2.example.local app3.example.local" | sudo tee -a /etc/hosts
curl -H "Host: app1.example.local" http://localhost:30080

# Delete cluster
bin/k8s-local down
```

## Available Modules

### Infrastructure Modules (ap-northeast-1)

| Module | Description |
|--------|-------------|
| `network` | VPC, Subnets, IGW, NAT Gateway |
| `routing` | Route Tables |
| `security-group` | Security Groups |
| `cluster` | k3s cluster on NixOS |
| `alb` | Application Load Balancer |
| `eice` | EC2 Instance Connect Endpoint |
| `efs` | EFS One Zone for persistent storage |
| `rds` | RDS PostgreSQL (optional) |
| `cognito` | Cognito User Pool (optional) |
| `github-oidc` | GitHub Actions OIDC (optional) |

### Global Modules (us-east-1)

| Module | Description |
|--------|-------------|
| `cloudfront` | CloudFront Distribution + WAF |

### Deploy Single Module

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

## Common Tasks

### Update Applications

```bash
# Edit manifests in k8s/<app-name>/
# Then redeploy
AWS_PROFILE=conao3.k8s bin/k8s deploy
```

### Add New Application

1. Create `k8s/my-app/` with deployment.yaml, service.yaml, ingress.yaml
2. Deploy: `AWS_PROFILE=conao3.k8s bin/k8s deploy`

See [007-ingress-subdomain.md](docs/007-ingress-subdomain.md#add-new-application) for details.

### View Logs

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs <pod-name> --tail=50'
```

### Check Resources

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -A'
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl top nodes'
```

### Update Cloudflare IP List

```bash
# Update Cloudflare IP ranges used for CloudFront WAF IP restriction
make update-cloudflare-ips

# Then redeploy CloudFront stack
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy cloudfront
```

### Build Custom AMI

```bash
# Using GitHub Actions (recommended)
gh workflow run build-ami.yml
gh run watch $(gh run list --workflow=build-ami.yml --limit=1 --json databaseId --jq '.[0].databaseId')
AWS_PROFILE=conao3.k8s ./bin/image deploy
```

See [004-nixos-ami.md](docs/004-nixos-ami.md) for details.

## Troubleshooting

### Application Not Accessible

1. Check ALB target health:
   ```bash
   AWS_PROFILE=conao3.k8s aws elbv2 describe-target-health \
     --target-group-arn $(aws cloudformation list-exports \
       --query "Exports[?Name=='dev-k8s-TargetGroup'].Value" --output text)
   ```

2. Check Traefik logs:
   ```bash
   AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs -n traefik -l app=traefik --tail=50'
   ```

3. Check pod status:
   ```bash
   AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -A'
   ```

### Infrastructure Issues

Check CloudFormation stack events:

```bash
AWS_PROFILE=conao3.k8s aws cloudformation describe-stack-events \
  --stack-name dev-k8s-<module-name> --max-items 10
```

## Technologies Used

- **Infrastructure**: AWS (EC2, VPC, ALB, CloudFront, EFS, WAF)
- **Kubernetes**: k3s (lightweight Kubernetes)
- **OS**: NixOS (declarative system configuration)
- **Ingress**: Traefik (v2.11)
- **Monitoring**: Prometheus, Grafana, Node Exporter, kube-state-metrics
- **IaC**: Clojure + CloudFormation
- **CI/CD**: GitHub Actions

## Project Structure

```
.
├── docs/                    # Documentation
├── k8s/                     # Kubernetes manifests
│   ├── traefik/            # Ingress Controller
│   ├── app1/, app2/, app3/ # Sample applications
│   ├── prometheus/         # Metrics collection
│   ├── grafana/            # Metrics visualization
│   └── aws-efs-csi-driver/ # EFS storage driver
├── nixos/                   # NixOS configuration
│   ├── nixos-configuration.nix  # Common config
│   └── hosts/              # Host-specific configs
├── src/                     # Infrastructure code (Clojure)
│   └── conao3/aws_infra_k8s/
│       ├── network.clj     # VPC, subnets
│       ├── alb.clj         # Load balancer
│       ├── cluster.clj     # k3s cluster
│       └── cloudfront.clj  # CDN + WAF
├── bin/                     # Helper scripts
│   ├── image               # AMI build/upload
│   ├── k8s/                # k8s deployment scripts
│   └── ssh/                # SSH access scripts
└── README.md                # This file
```

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## License

See LICENSE file for details.

## Support

For issues or questions:
- Check the [documentation](docs/)
- Search existing GitHub issues
- Open a new issue with details

## Acknowledgments

- Built with [k3s](https://k3s.io/)
- Powered by [NixOS](https://nixos.org/)
- Infrastructure code in [Clojure](https://clojure.org/)
