# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular approach to deploying AWS infrastructure components. Each module handles a specific aspect of the infrastructure and can be deployed independently or all at once.

## Architecture

```
Internet
  ↓ HTTPS
CloudFront (CDN + WAF Free Plan)
  ↓ HTTP (CloudFront Prefix List only)
Application Load Balancer
  ↓ NodePort 30000-32767
EC2 (k3s cluster on NixOS)
  ↓
Kubernetes Pods
```

**Security Features:**
- CloudFront WAF with 3 rules (Rate Limit, Geo Block, SQLi Protection)
- ALB accessible only from CloudFront (Managed Prefix List)
- DDoS protection enabled by default
- HTTPS enforced at CloudFront edge

All AWS operations use the `AWS_PROFILE` environment variable to specify credentials. Set it before running any commands:

```bash
export AWS_PROFILE=conao3.k8s
# or prefix each command:
AWS_PROFILE=conao3.k8s <command>
```

## Pre-requires

### Add keypair

```bash
aws ec2 create-key-pair --key-name dev-k8s-keypair --query 'KeyMaterial' --output text --profile conao3.k8s > ~/.ssh/dev-k8s-keypair.pem
chmod 400 ~/.ssh/dev-k8s-keypair.pem
```

### Add S3 bucket and VM Import role

Deploy S3 bucket and VM Import role (required for custom AMI upload):
```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy s3
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy vm-import
```

### Add secret for RDS

```
cat <<EOF > /tmp/dev-k8s-secret.json
{"rds-postgres-password":"ChangeMe12345"}
EOF

aws secretsmanager create-secret --name dev-k8s-secret --secret-string file:///tmp/dev-k8s-secret.json --profile conao3.k8s
rm /tmp/dev-k8s-secret.json
```

Update secret.

```
aws secretsmanager get-secret-value --secret-id dev-k8s-secret --query SecretString --output text --profile conao3.k8s | jq . > /tmp/dev-k8s-secret.json
$EDITOR /tmp/dev-k8s-secret.json
aws secretsmanager update-secret --secret-id dev-k8s-secret --secret-string file:///tmp/dev-k8s-secret.json --profile conao3.k8s
rm /tmp/dev-k8s-secret.json
```

## Deployment

### Deploy All Modules

Deploy all infrastructure components:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

This deploys:
- **ap-northeast-1**: Network, ALB, Cluster (k3s), etc.
- **us-east-1**: CloudFront + WAF (automatically deployed to us-east-1)

**CloudFront Free Plan Limits:**
- 1M requests/month
- 100GB data transfer/month
- 5 WAF rules max (currently using 3)
- No overage charges (performance throttling instead)

### Deploy Individual Module

Available modules:

**Infrastructure (ap-northeast-1):**
- `s3` (S3 bucket for VM Import)
- `vm-import` (VM Import IAM role for custom AMI upload)
- `network`
- `routing`
- `security-group`
- `cluster` (k3s on NixOS)
- `alb` (Application Load Balancer)
- `eice` (EC2 Instance Connect Endpoint)
- `github-oidc` (GitHub Actions OIDC provider and IAM role)
- `rds`
- `cognito`
- `efs` (EFS One Zone for cost-effective persistent storage)
- `ssh-tunnel` (not included in `deploy all`)
- `ami-builder` (not included in `deploy all`)

**Global Resources (us-east-1):**
- `cloudfront` (CloudFront + WAF, automatically deployed to us-east-1)
- `budget` (AWS Budget with email notifications, automatically deployed to us-east-1)

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

For example, to deploy only the routing module:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```

## Custom NixOS AMI

### NixOS Configuration Structure

```
nixos/
├── nixos-configuration.nix  # Common configuration
└── hosts/
    ├── ec2-image.nix        # EC2-specific configuration (aarch64)
    └── vm.nix               # VM-specific configuration (x86_64)
```

### Build and Deploy Custom AMI

You can build and deploy a custom NixOS AMI (aarch64) using the configuration in `nixos/hosts/ec2-image.nix`.

#### Local Build (requires Nix with aarch64 support)

```bash
AWS_PROFILE=conao3.k8s bin/image build    # Build the custom image
AWS_PROFILE=conao3.k8s bin/image upload   # Upload to S3, import snapshot, and register as AMI
AWS_PROFILE=conao3.k8s bin/image deploy   # Deploy cluster with custom AMI
```

The `upload` command:
- Saves the AMI ID to SSM Parameter Store (`/dev-k8s/custom-ami-id`)
- Creates a local file `target/ami-id.txt` with the AMI ID

#### GitHub Actions Build (Recommended)

Use the **Build NixOS AMI** workflow in the GitHub Actions tab to build the AMI on ARM64 runners. The workflow automatically:
1. Builds the NixOS image natively on aarch64
2. Uploads to S3 and imports as a snapshot
3. Registers the AMI
4. Saves the AMI ID to SSM Parameter Store

The cluster deployment automatically uses the AMI ID from SSM Parameter Store (`/dev-k8s/custom-ami-id`).

```sh
gh workflow run build-ami.yml
sleep 5
gh run watch $(gh run list --workflow=build-ami.yml --limit=1 --json databaseId --jq '.[0].databaseId')
AWS_PROFILE=conao3.k8s ./bin/image deploy
```

### Test Custom Image with VM

You can test your custom NixOS configuration locally using QEMU before deploying to AWS.

```bash
nix run .#vm
```

Or with custom resources:
```bash
QEMU_OPTS="-m 8192 -smp 8" nix run .#vm
```

Default configuration: 4GB RAM, 4 CPU cores

To exit, press `Ctrl-A` then `X`.

### Search Official NixOS AMI

Ref: https://nixos.org/download/#nixos-amazon

```bash
aws ec2 describe-images --owners 427812963091 --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=arm64' --query 'sort_by(Images, &CreationDate)[-1].[ImageId,Name]' --output text --profile conao3.k8s
```

Default AMI: `ami-00ce0dbbbd1a71d5b` (nixos/25.05.813814.ac62194c3917-aarch64-linux)

## Persistent Storage with EFS

This project uses AWS Elastic File System (EFS) One Zone with the AWS EFS CSI Driver for persistent storage in Kubernetes.

### Features

- **EFS One Zone**: Cost-effective single-AZ storage (~$0.19/GB/month, about 47% cheaper than Standard)
- **ReadWriteMany**: Multiple pods can read and write to the same volume simultaneously
- **Automatic Provisioning**: StorageClass automatically creates EFS Access Points
- **Single-AZ Deployment**: EFS in ap-northeast-1a (same AZ as cluster)
- **Encrypted**: EFS file system is encrypted at rest
- **Bursting Performance**: Throughput scales with file system size

### Architecture

```
Kubernetes Pod
  ↓ (mount)
EFS CSI Driver (DaemonSet on each node)
  ↓ (NFS mount)
EFS Mount Target (ap-northeast-1a)
  ↓
EFS One Zone File System (ap-northeast-1a)
```

### Cost Comparison

**EFS Pricing in ap-northeast-1:**
- EFS Standard: ~$0.36/GB/month (multi-AZ replication)
- EFS One Zone: ~$0.19/GB/month (single-AZ, 47% cheaper)

For a single-node cluster in one AZ, EFS One Zone provides significant cost savings without sacrificing functionality.

### Usage

**Using EFS in your application:**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: my-app-storage
spec:
  accessModes:
  - ReadWriteMany
  storageClassName: efs-sc
  resources:
    requests:
      storage: 10Gi
```

The EFS CSI Driver automatically creates an EFS Access Point for each PVC.

## Kubernetes Applications

### Directory Structure

```
k8s/
├── nginx/
│   ├── deployment.yaml
│   └── service.yaml
├── kubernetes-dashboard/
│   ├── kustomization.yaml
│   ├── admin-user.yaml
│   └── service-patch.yaml
├── node-exporter/
│   ├── daemonset.yaml
│   └── service.yaml
├── kube-state-metrics/
│   ├── service-account.yaml
│   ├── cluster-role.yaml
│   ├── cluster-role-binding.yaml
│   ├── deployment.yaml
│   └── service.yaml
├── prometheus/
│   ├── configmap.yaml
│   ├── rbac.yaml
│   ├── deployment.yaml
│   └── service.yaml
├── grafana/
│   ├── pvc.yaml
│   ├── pvc-efs.yaml
│   ├── datasource-configmap.yaml
│   ├── deployment.yaml
│   └── service.yaml
└── aws-efs-csi-driver/
    ├── service-account.yaml
    ├── rbac.yaml
    ├── csidriver.yaml
    ├── controller.yaml
    ├── node.yaml
    └── storageclass.yaml
```

### Local Development (kind)

Test Kubernetes manifests locally using kind:

```bash
# Create local cluster
bin/k8s/local up

# Deploy applications
bin/k8s/local deploy

# Get Dashboard token
bin/k8s/local token

# Access points
# nginx: http://localhost:30924
# Dashboard: https://localhost:31353
# Prometheus: http://localhost:30900
# Grafana: http://localhost:30300 (admin/admin)

# Delete cluster
bin/k8s/local down
```

### Monitoring Stack

The monitoring stack consists of:

**Node Exporter (DaemonSet)**
- Runs on every node in the cluster
- Collects host machine metrics:
  - CPU usage and load
  - Memory and swap usage
  - Disk I/O and space
  - Network traffic
  - Filesystem statistics
- Exposes metrics on port 9100
- Uses `hostNetwork: true` and `hostPID: true` for accurate host metrics
- Mounts `/proc`, `/sys`, and `/` from the host

**kube-state-metrics**
- Exposes Kubernetes object state metrics
- Provides information about:
  - Deployments (replicas, conditions, status)
  - Pods (phase, conditions, restarts, resource requests/limits)
  - Nodes (capacity, allocatable, conditions)
  - Services, ConfigMaps, Secrets
  - Jobs, CronJobs, DaemonSets, StatefulSets
- Runs in kube-system namespace
- Exposes metrics on port 8080

**Prometheus**
- Scrapes metrics from:
  - Kubernetes API server
  - Kubernetes nodes
  - Node Exporter pods (host metrics)
  - kube-state-metrics (Kubernetes object metrics)
  - Pods with `prometheus.io/scrape: "true"` annotation
- Stores time-series data locally
- Query metrics at: http://localhost:30900 (local) or via CloudFront

**Grafana**
- Visualizes Prometheus metrics
- Default credentials: admin/admin
- Access at: http://localhost:30300 (local) or via CloudFront
- Data persistence:
  - Local (kind): PersistentVolumeClaim with local-path-provisioner (1Gi)
  - AWS (k3s): PersistentVolumeClaim with EFS CSI Driver (5Gi, ReadWriteMany)
- Auto-configured: Prometheus data source at http://prometheus:9090
- All dashboards, settings, and users are persisted across Pod restarts

To view host metrics in Prometheus:
1. Open http://localhost:30900
2. Query examples:
   - `node_cpu_seconds_total` - CPU usage
   - `node_memory_MemAvailable_bytes` - Available memory
   - `node_disk_io_time_seconds_total` - Disk I/O
   - `node_network_receive_bytes_total` - Network received

### Using Grafana

#### 1. Access Grafana
- Local: http://localhost:30300
- Login: admin/admin (change password on first login)

**Data Persistence:**
- Grafana settings are stored in PersistentVolumeClaim
- Dashboards, data sources, user settings, alerts are all persisted
- Settings are retained after Pod restart/deletion
- Local (kind): 1Gi, uses local-path-provisioner
- AWS (k3s): 5Gi, uses AWS EFS with CSI Driver (ReadWriteMany)

#### 2. Prometheus Data Source (Auto-configured)

The Prometheus data source is automatically configured. No manual setup required.

To verify:
1. Open "Connections" → "Data sources"
2. Confirm "Prometheus" is already configured

#### 3. Create Dashboard for Host Metrics

**Option A: Import Pre-built Dashboard**

1. Click "Dashboards" → "Import" in the left menu
2. Enter dashboard ID: `1860` (Node Exporter Full)
3. Click "Load"
4. Select Prometheus data source
5. Click "Import"

This dashboard includes:
- CPU usage (per core and total)
- Memory usage and available
- Disk I/O and space
- Network traffic
- System load
- Filesystem statistics

**Option B: Create Custom Dashboard**

1. Click "Dashboards" → "New" → "New Dashboard"
2. Click "Add visualization"
3. Select Prometheus data source
4. Add queries:

**CPU Usage:**
```promql
100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

**Memory Usage:**
```promql
100 - ((node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100)
```

**Disk Usage:**
```promql
100 - ((node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100)
```

**Network Traffic (Received):**
```promql
rate(node_network_receive_bytes_total{device!~"lo|veth.*"}[5m])
```

**Network Traffic (Transmitted):**
```promql
rate(node_network_transmit_bytes_total{device!~"lo|veth.*"}[5m])
```

5. Customize visualization type (Graph, Gauge, Stat, etc.)
6. Click "Save dashboard"

#### 4. Create Dashboard for Kubernetes Deployments

**Option A: Import Pre-built Dashboard**

1. Click "Dashboards" → "Import" in the left menu
2. Enter dashboard ID: `15661` (Kubernetes Deployments)
3. Select Prometheus data source
4. Click "Import"

**Option B: Create Custom Dashboard**

1. Click "Dashboards" → "New" → "New Dashboard"
2. Add these visualizations:

**Deployment Replicas (Available vs Desired):**
```promql
kube_deployment_status_replicas_available
```
```promql
kube_deployment_spec_replicas
```

**Pod Status by Deployment:**
```promql
sum(kube_pod_status_phase{phase="Running"}) by (namespace)
```

**Pod Restart Count:**
```promql
sum(kube_pod_container_status_restarts_total) by (namespace, pod)
```

**Pods by Phase:**
```promql
count(kube_pod_status_phase) by (phase)
```

**Deployment Conditions:**
```promql
kube_deployment_status_condition{condition="Available"}
```

**Container Resource Requests (CPU):**
```promql
sum(kube_pod_container_resource_requests{resource="cpu"}) by (namespace, pod)
```

**Container Resource Limits (Memory):**
```promql
sum(kube_pod_container_resource_limits{resource="memory"}) by (namespace, pod)
```

**Failed Pods:**
```promql
count(kube_pod_status_phase{phase="Failed"})
```

#### 5. Recommended Dashboards

**Essential (Import these first):**
- **1860**: Node Exporter Full (host metrics)
- **15172**: Kubernetes Cluster Monitoring (deployments, pods)

**Optional (for detailed metrics):**
- **13332**: Kube State Metrics v2
- **315**: Kubernetes Cluster Monitoring
- **6417**: Kubernetes Cluster (Prometheus)
- **15661**: Kubernetes Deployments
- **8588**: Kubernetes Deployment Statefulset Daemonset metrics

**Troubleshooting Gateway Timeout:**

If importing by dashboard ID fails with "gateway timeout", use pre-downloaded JSON files:

1. Navigate to `grafana-dashboards/` directory
2. In Grafana, click "Dashboards" → "Import"
3. Click "Upload JSON file"
4. Select the JSON file (e.g., `1860-node-exporter-full.json`)
5. Choose Prometheus data source
6. Click "Import"

See `grafana-dashboards/README.md` for detailed instructions.

#### 6. Tips

- Use **time range selector** (top right) to change time window
- Use **refresh interval** dropdown to auto-refresh
- Use **variables** to filter by node, pod, namespace
- Click on graph legends to show/hide series
- Use **Explore** view to test PromQL queries before adding to dashboard

### Deploy to AWS

Deploy all Kubernetes applications to AWS:

```bash
AWS_PROFILE=conao3.k8s bin/k8s/deploy
```

This deploys:
- nginx (NodePort 30924)
- Kubernetes Dashboard (NodePort 31353)
- Node Exporter (DaemonSet, monitors host metrics)
- kube-state-metrics (exposes Kubernetes object metrics)
- Prometheus (NodePort 30900, collects metrics)
- AWS EFS CSI Driver (persistent storage with EFS)
- Grafana (NodePort 30300, visualizes metrics, data stored in EFS)

### Access Applications on AWS

#### Grafana
```bash
# Port forward Grafana (runs in foreground)
AWS_PROFILE=conao3.k8s bin/ssh/node grafana

# Access: http://localhost:30300
# Login: admin/admin
```

#### Prometheus
```bash
# Port forward Prometheus (runs in foreground)
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus

# Access: http://localhost:30900
```

#### Kubernetes Dashboard
1. Get the admin token:
```bash
AWS_PROFILE=conao3.k8s bin/k8s/get-dashboard-token
```

2. Port forward Dashboard:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node dashboard
```

3. Access: https://localhost:31353

4. Login with the token from step 1

#### kubectl Access to AWS Cluster
```bash
# In terminal 1: Port forward Kubernetes API
AWS_PROFILE=conao3.k8s bin/ssh/node k8s

# In terminal 2: Get kubeconfig and use kubectl
bin/ssh/node login 'cat /etc/rancher/k3s/k3s.yaml' > /tmp/k3s.yaml
sed -i 's|https://127.0.0.1:6443|https://localhost:6443|g' /tmp/k3s.yaml
export KUBECONFIG=/tmp/k3s.yaml
kubectl get pods -A
```

### Access via CloudFront

Applications are accessible via:
- nginx: https://xxx.cloudfront.net/
- Kubernetes Dashboard: Configure ALB listener rules to route `/dashboard` path

## SSH Access

Connect to instances via EC2 Instance Connect Endpoint (EICE).

### AMI Builder Instance
```bash
AWS_PROFILE=conao3.k8s bin/ssh/ami-builder
```

### Cluster Node Instance

**Available Commands:**
```bash
# SSH login (default)
AWS_PROFILE=conao3.k8s bin/ssh/node
AWS_PROFILE=conao3.k8s bin/ssh/node login

# Port forward Grafana web console
AWS_PROFILE=conao3.k8s bin/ssh/node grafana

# Port forward Prometheus web console
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus

# Port forward Kubernetes Dashboard
AWS_PROFILE=conao3.k8s bin/ssh/node dashboard

# Port forward Kubernetes API (for kubectl access)
AWS_PROFILE=conao3.k8s bin/ssh/node k8s

# Show help
AWS_PROFILE=conao3.k8s bin/ssh/node help
```

All scripts use EICE to establish secure SSH connections without requiring public IP addresses.

## License

See LICENSE file for details.
