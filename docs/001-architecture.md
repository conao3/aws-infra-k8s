# Architecture

## Overview

This project provides a modular approach to deploying AWS infrastructure for Kubernetes. The architecture is designed with security, cost-efficiency, and simplicity in mind.

## Infrastructure Diagram

```
Internet
  ↓ HTTPS
CloudFront (CDN + WAF Free Plan)
  ↓ HTTP (CloudFront Prefix List only)
Application Load Balancer
  ↓ NodePort 30080
Traefik Ingress Controller
  ↓
Kubernetes Services
  ↓
Kubernetes Pods (k3s on NixOS)
```

## Components

### Edge Layer

**CloudFront (CDN + WAF)**
- Global content delivery network
- HTTPS termination at edge locations
- WAF with 3 rules (Rate Limit, Geo Block, SQLi Protection)
- Free Plan: 1M requests/month, 100GB data transfer/month
- DDoS protection enabled by default

### Application Layer

**Application Load Balancer**
- HTTP-only traffic from CloudFront
- Restricted access via CloudFront Managed Prefix List
- Single target group pointing to Traefik (NodePort 30080)
- Health checks on Traefik endpoint

**Traefik Ingress Controller**
- Kubernetes-native ingress controller
- Host-based routing to multiple applications
- Deployed as Deployment in `traefik` namespace
- Exposed via NodePort 30080

### Compute Layer

**EC2 Instances (k3s cluster on NixOS)**
- Single-node k3s cluster (can be scaled)
- NixOS for declarative system configuration
- Managed via AutoScaling Group
- Custom AMI built with GitHub Actions

**Kubernetes Applications**
- app1, app2, app3: Static HTML applications
- Monitoring: Prometheus, Grafana, Node Exporter, kube-state-metrics
- Kubernetes Dashboard

### Storage Layer

**EFS One Zone**
- Cost-effective persistent storage (~$0.19/GB/month)
- Single-AZ deployment (ap-northeast-1a)
- AWS EFS CSI Driver for dynamic provisioning
- Used by Grafana for data persistence

**EBS Volumes**
- Root volume for EC2 instances
- 10GB gp3 volumes

## Security Features

### Network Security

1. **CloudFront → ALB Restriction**
   - ALB accepts traffic only from CloudFront Managed Prefix List
   - Prevents direct access to ALB

2. **Security Groups**
   - Minimal open ports
   - ALB: Port 80 from CloudFront only
   - EC2: Port 6443 (k8s API), 30000-32767 (NodePorts)

3. **No Public IPs**
   - EC2 instances in private subnets
   - Access via EC2 Instance Connect Endpoint (EICE)

### Application Security

1. **WAF Rules**
   - Rate Limiting: 2000 requests/5min per IP
   - Geographic Blocking: Allow only JP and US
   - SQLi Protection: AWS Managed Rule Set

2. **HTTPS Enforcement**
   - HTTPS enforced at CloudFront edge
   - HTTP → HTTPS redirect

3. **DDoS Protection**
   - Enabled by default on CloudFront

## Region Strategy

### ap-northeast-1 (Tokyo)

Main infrastructure region:
- VPC and networking
- EC2 instances (k3s cluster)
- ALB
- EFS One Zone
- RDS (if deployed)
- All compute and storage resources

### us-east-1 (N. Virginia)

Global resources:
- CloudFront Distribution
- WAF WebACL (must be in us-east-1 for CloudFront)
- ACM certificates for CloudFront (if using custom domains)

## Cost Optimization

### Free Tier Usage

- CloudFront: Free Plan (1M requests, 100GB transfer/month)
- WAF: Free Plan (3 rules)
- EICE: Included in EC2 pricing

### Cost-Effective Choices

- EFS One Zone: 47% cheaper than EFS Standard
- Single-node k3s: Minimal compute costs
- gp3 EBS: Cost-effective storage
- t4g instances: ARM-based, cost-effective

### Monthly Cost Estimate

| Resource | Cost |
|----------|------|
| EC2 t4g.small | ~$12 |
| ALB | ~$18 |
| EBS 10GB | ~$1 |
| EFS (per GB) | ~$0.19/GB |
| Data Transfer | Variable |
| **Total (minimal)** | **~$31/month** |

## Scalability Considerations

### Current Limitations

- Single-node k3s cluster
- NodePort-based service exposure
- Manual scaling required

### Scaling Options

1. **Horizontal Pod Scaling**
   - Increase replicas in Deployments
   - Limited by single-node capacity

2. **Vertical Scaling**
   - Switch to larger EC2 instance type
   - Update AutoScaling Group configuration

3. **Multi-Node Cluster**
   - Add more nodes to AutoScaling Group
   - k3s automatically joins nodes to cluster
   - Requires shared storage consideration

4. **AWS Load Balancer Controller**
   - Replace Traefik + ALB with ALB per Ingress
   - Direct pod routing
   - More advanced features (Cognito auth, WAF per ALB)

## References

- [k3s Documentation](https://docs.k3s.io/)
- [Traefik Documentation](https://doc.traefik.io/traefik/)
- [AWS CloudFront Free Plan](https://aws.amazon.com/cloudfront/pricing/)
- [AWS EFS Pricing](https://aws.amazon.com/efs/pricing/)
