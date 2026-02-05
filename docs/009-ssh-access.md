# SSH Access

This document describes how to access EC2 instances via EC2 Instance Connect Endpoint (EICE).

## Overview

All EC2 instances are in private subnets without public IP addresses. Access is provided through EC2 Instance Connect Endpoint (EICE), which provides secure SSH access without exposing instances to the internet.

## Benefits of EICE

- **No Public IPs**: Instances remain in private subnets
- **No Bastion Host**: No need to maintain a separate bastion instance
- **IAM-Based**: Access controlled via IAM policies
- **Audit Trail**: All connections logged in CloudTrail
- **Cost-Effective**: Included in EC2 pricing

## Available Commands

### Cluster Node Instance

The `bin/ssh/node` script provides multiple commands:

#### SSH Login

```bash
# Default: opens SSH session
AWS_PROFILE=conao3.k8s bin/ssh/node

# Explicit login
AWS_PROFILE=conao3.k8s bin/ssh/node login

# Run single command
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -A'
```

#### Port Forwarding

**Grafana**:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node grafana
# Access: http://localhost:30300
```

**Prometheus**:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus
# Access: http://localhost:30900
```

**Kubernetes Dashboard**:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node dashboard
# Access: https://localhost:31353
```

**Kubernetes API** (for kubectl):
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node k8s
# Then use kubectl with forwarded port
```

#### Help

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node help
```

### AMI Builder Instance

Access the AMI builder instance (if deployed):

```bash
AWS_PROFILE=conao3.k8s bin/ssh/ami-builder
```

## kubectl Access to AWS Cluster

To use kubectl from your local machine with the AWS cluster:

**Terminal 1**: Port forward Kubernetes API

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node k8s
```

**Terminal 2**: Get kubeconfig and use kubectl

```bash
# Get kubeconfig from cluster
AWS_PROFILE=conao3.k8s bin/ssh/node login 'cat /etc/rancher/k3s/k3s.yaml' > /tmp/k3s.yaml

# Update server URL to use forwarded port
sed -i 's|https://127.0.0.1:6443|https://localhost:6443|g' /tmp/k3s.yaml

# Now kubectl works with AWS cluster
KUBECONFIG=/tmp/k3s.yaml kubectl get pods -A
KUBECONFIG=/tmp/k3s.yaml kubectl get nodes
```

## How It Works

### Connection Flow

```
Your Machine
  ↓ SSH via EICE
EC2 Instance Connect Endpoint (in VPC)
  ↓ Private network
EC2 Instance (private subnet)
```

### Script Operation

The `bin/ssh/node` script:

1. Queries AutoScaling Group for healthy instances
2. Gets the first available instance ID
3. Gets the instance's private IP
4. Uses EICE to establish SSH connection
5. Executes requested command or port forward

### EICE ProxyCommand

The script uses AWS CLI's `ec2-instance-connect open-tunnel`:

```bash
ssh -i ~/.ssh/dev-k8s-keypair.pem \
  -o "ProxyCommand=aws ec2-instance-connect open-tunnel --instance-id i-xxxxx --remote-port 22" \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  root@<private-ip>
```

## Common Tasks

### View Logs

```bash
# Pod logs
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs <pod-name>'

# Deployment logs
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs -l app=<app-name> --tail=50'

# System logs
AWS_PROFILE=conao3.k8s bin/ssh/node login 'journalctl -u k3s -n 50'
```

### Check System Resources

```bash
# CPU and memory
AWS_PROFILE=conao3.k8s bin/ssh/node login 'top -bn1 | head -20'

# Disk usage
AWS_PROFILE=conao3.k8s bin/ssh/node login 'df -h'

# Disk I/O
AWS_PROFILE=conao3.k8s bin/ssh/node login 'iostat -x 1 5'
```

### Manage Kubernetes

```bash
# Get all resources
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get all -A'

# Describe pod
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl describe pod <pod-name>'

# Delete pod (will be recreated by deployment)
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl delete pod <pod-name>'

# Restart deployment
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl rollout restart deployment/<name>'
```

### Check k3s Status

```bash
# k3s service status
AWS_PROFILE=conao3.k8s bin/ssh/node login 'systemctl status k3s'

# k3s version
AWS_PROFILE=conao3.k8s bin/ssh/node login 'k3s --version'

# Check k3s logs
AWS_PROFILE=conao3.k8s bin/ssh/node login 'journalctl -u k3s --since "1 hour ago"'
```

## Troubleshooting

### Connection Refused

**Error**: `Connection refused` or `Connection timed out`

**Causes**:
1. Instance not running
2. EICE not deployed
3. Security group blocking traffic
4. Key pair mismatch

**Debug**:

```bash
# Check instance status
AWS_PROFILE=conao3.k8s aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=dev-k8s-node-*" \
  --query 'Reservations[*].Instances[*].[InstanceId,State.Name,PrivateIpAddress]' \
  --output table

# Check EICE
AWS_PROFILE=conao3.k8s aws ec2 describe-instance-connect-endpoints

# Check security groups
AWS_PROFILE=conao3.k8s aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=dev-k8s-*"
```

### No Healthy Instances

**Error**: `No healthy InService instance found`

**Causes**:
1. AutoScaling Group has no instances
2. All instances are unhealthy
3. Instances still launching

**Debug**:

```bash
# Check AutoScaling Group
AWS_PROFILE=conao3.k8s aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names dev-k8s-node

# Check instance health
AWS_PROFILE=conao3.k8s aws autoscaling describe-auto-scaling-instances
```

### Permission Denied

**Error**: `Permission denied (publickey)`

**Causes**:
1. Key pair not found
2. Wrong key pair
3. Key permissions too open

**Fix**:

```bash
# Check key exists
ls -l ~/.ssh/dev-k8s-keypair.pem

# Fix permissions
chmod 400 ~/.ssh/dev-k8s-keypair.pem
```

### Port Forward Not Working

**Error**: Port forward disconnects or hangs

**Causes**:
1. Service not running on target port
2. Network connectivity issue

**Debug**:

```bash
# Check if service is listening
AWS_PROFILE=conao3.k8s bin/ssh/node login 'netstat -tlnp | grep 30300'

# Check pod status
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -l app=grafana'
```

## Security Considerations

1. **IAM Permissions**: Users need `ec2-instance-connect:OpenTunnel` permission
2. **Key Rotation**: Regularly rotate SSH key pairs
3. **Audit Logs**: Monitor CloudTrail for EC2 Instance Connect events
4. **Principle of Least Privilege**: Grant SSH access only to required users
5. **Session Recording**: Consider using AWS Systems Manager Session Manager for session recording

## Alternative: Systems Manager Session Manager

For additional security and session recording, consider using AWS Systems Manager Session Manager:

**Pros**:
- No SSH keys required
- Session recording
- Additional audit capabilities

**Cons**:
- Requires SSM Agent on instances
- Additional IAM configuration

## Cleanup

When instances are terminated, EICE connections automatically close. No manual cleanup needed.
