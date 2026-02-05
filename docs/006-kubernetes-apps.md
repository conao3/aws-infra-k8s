# Kubernetes Applications

This document describes the Kubernetes applications deployed in this project.

## Directory Structure

```
k8s/
├── traefik/           # Ingress Controller
├── app1/              # Sample app 1 (static HTML)
├── app2/              # Sample app 2 (static HTML)
├── app3/              # Sample app 3 (static HTML)
├── kubernetes-dashboard/  # Kubernetes Dashboard
├── node-exporter/     # Host metrics exporter
├── kube-state-metrics/    # K8s object metrics
├── prometheus/        # Metrics collection
├── grafana/           # Metrics visualization
└── aws-efs-csi-driver/    # EFS storage driver
```

## Applications

### Traefik (Ingress Controller)

**Purpose**: Routes external traffic to internal services based on hostname

**Namespace**: `traefik`

**Components**:
- Deployment: Traefik v2.11
- Service: NodePort 30080 (HTTP), 30081 (Dashboard)
- RBAC: ServiceAccount, ClusterRole, ClusterRoleBinding
- IngressClass: `traefik` (default)

**Configuration**:
```yaml
args:
- --api.insecure=true
- --providers.kubernetesingress=true
- --entrypoints.web.address=:80
- --log.level=INFO
```

See [007-ingress-subdomain.md](007-ingress-subdomain.md) for Ingress configuration.

### Sample Applications (app1, app2, app3)

**Purpose**: Demonstrate multi-app hosting with subdomain routing

**Namespace**: `default`

**Components per app**:
- Deployment: nginx with custom HTML (via ConfigMap)
- Service: ClusterIP
- Ingress: Host-based routing

**Hostnames**:
- app1: `app1.example.com`
- app2: `app2.example.com`
- app3: `app3.example.com`

**Add New App**:

1. Create directory `k8s/my-app/`
2. Add manifests (deployment.yaml, service.yaml, ingress.yaml)
3. Deploy: `AWS_PROFILE=conao3.k8s bin/k8s/deploy`

See [007-ingress-subdomain.md](007-ingress-subdomain.md#add-new-application) for details.

### Kubernetes Dashboard

**Purpose**: Web UI for cluster management

**Namespace**: `kubernetes-dashboard`

**Components**:
- Deployment: kubernetes-dashboard, dashboard-metrics-scraper
- Service: NodePort 31353 (HTTPS)
- RBAC: admin-user with cluster-admin role

**Access**:

```bash
# Get token
AWS_PROFILE=conao3.k8s bin/k8s/get-dashboard-token

# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node dashboard

# Open browser: https://localhost:31353
```

### Node Exporter

**Purpose**: Export host-level metrics (CPU, memory, disk, network)

**Namespace**: `default`

**Components**:
- DaemonSet: Runs on every node
- Service: ClusterIP on port 9100

**Metrics Collected**:
- CPU usage and load
- Memory and swap usage
- Disk I/O and space
- Network traffic
- Filesystem statistics

**Configuration**:
- `hostNetwork: true` - Access host network
- `hostPID: true` - Access host processes
- Mounts `/proc`, `/sys`, `/` from host

### kube-state-metrics

**Purpose**: Export Kubernetes object state metrics

**Namespace**: `kube-system`

**Components**:
- Deployment: kube-state-metrics
- Service: ClusterIP on port 8080
- RBAC: ClusterRole with read access to all resources

**Metrics Collected**:
- Deployments (replicas, conditions, status)
- Pods (phase, conditions, restarts, resources)
- Nodes (capacity, allocatable, conditions)
- Services, ConfigMaps, Secrets
- Jobs, CronJobs, DaemonSets, StatefulSets

### Prometheus

**Purpose**: Collect and store time-series metrics

**Namespace**: `default`

**Components**:
- Deployment: Prometheus server
- Service: NodePort 30900
- ConfigMap: Scrape configuration
- RBAC: ClusterRole for k8s API access

**Scrape Targets**:
- Kubernetes API server
- Kubernetes nodes (kubelet)
- Node Exporter (host metrics)
- kube-state-metrics (k8s object metrics)
- Pods with `prometheus.io/scrape: "true"` annotation

**Access**:

```bash
# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus

# Open browser: http://localhost:30900
```

See [008-monitoring.md](008-monitoring.md) for query examples.

### Grafana

**Purpose**: Visualize Prometheus metrics

**Namespace**: `default`

**Components**:
- Deployment: Grafana server
- Service: NodePort 30300
- PVC: EFS-backed storage (5Gi, ReadWriteMany)
- ConfigMap: Auto-configured Prometheus data source

**Credentials**: admin/admin (change on first login)

**Data Persistence**:
- All dashboards, settings, users stored in EFS
- Persists across pod restarts/deletions

**Access**:

```bash
# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node grafana

# Open browser: http://localhost:30300
```

See [008-monitoring.md](008-monitoring.md) for dashboard setup.

### AWS EFS CSI Driver

**Purpose**: Provide persistent storage via EFS

**Namespace**: `default`

**Components**:
- Deployment: efs-csi-controller
- DaemonSet: efs-csi-node (runs on every node)
- StorageClass: `efs-sc`
- RBAC: ServiceAccounts, ClusterRole, ClusterRoleBinding

**Features**:
- Dynamic provisioning of EFS Access Points
- ReadWriteMany support
- Automatic mounting to pods

See [005-persistent-storage.md](005-persistent-storage.md) for usage.

## Local Development

Test applications locally with kind:

```bash
# Create local cluster
bin/k8s/local up

# Deploy applications
bin/k8s/local deploy

# Get Dashboard token
bin/k8s/local token

# Access points
# Traefik Dashboard: http://localhost:30081
# Dashboard: https://localhost:31353
# Prometheus: http://localhost:30900
# Grafana: http://localhost:30300

# Delete cluster
bin/k8s/local down
```

### Test Subdomain Routing Locally

```bash
# Add to /etc/hosts
echo "127.0.0.1 app1.example.local app2.example.local app3.example.local" | sudo tee -a /etc/hosts

# Test with curl
curl -H "Host: app1.example.local" http://localhost:30080
curl -H "Host: app2.example.local" http://localhost:30080
curl -H "Host: app3.example.local" http://localhost:30080

# Or access in browser
http://app1.example.local:30080
http://app2.example.local:30080
http://app3.example.local:30080
```

## Deploy to AWS

```bash
AWS_PROFILE=conao3.k8s bin/k8s/deploy
```

This deploys all applications to the AWS k3s cluster.

## Update Applications

To update an application:

1. Edit manifests in `k8s/<app-name>/`
2. Redeploy: `AWS_PROFILE=conao3.k8s bin/k8s/deploy`

To update only a specific app:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl apply -f /tmp/k8s-manifests/app1/'
```

## Troubleshooting

### Pod Not Starting

Check pod events:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl describe pod <pod-name>'
```

### Service Not Accessible

Check service endpoints:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get endpoints <service-name>'
```

### Ingress Not Working

Check Traefik logs:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs -n traefik -l app=traefik'
```

Check Ingress status:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get ingress -A'
```
