# Monitoring

This document describes the monitoring stack and how to use it.

## Stack Overview

The monitoring stack consists of:
- **Node Exporter**: Host-level metrics (CPU, memory, disk, network)
- **kube-state-metrics**: Kubernetes object state metrics
- **Prometheus**: Metrics collection and storage
- **Grafana**: Metrics visualization

## Components

### Node Exporter

**Purpose**: Export host machine metrics

**Metrics Collected**:
- CPU usage and load
- Memory and swap usage
- Disk I/O and space
- Network traffic
- Filesystem statistics

**Deployment**: DaemonSet (runs on every node)

**Configuration**:
- `hostNetwork: true` - Access host network
- `hostPID: true` - Access host processes
- Mounts `/proc`, `/sys`, `/` from host

### kube-state-metrics

**Purpose**: Export Kubernetes object state

**Metrics Collected**:
- Deployments (replicas, conditions, status)
- Pods (phase, conditions, restarts, resource requests/limits)
- Nodes (capacity, allocatable, conditions)
- Services, ConfigMaps, Secrets
- Jobs, CronJobs, DaemonSets, StatefulSets

**Namespace**: `kube-system`

### Prometheus

**Purpose**: Collect and store time-series metrics

**Scrape Targets**:
- Kubernetes API server
- Kubernetes nodes (kubelet)
- Node Exporter (host metrics)
- kube-state-metrics (k8s object metrics)
- Pods with `prometheus.io/scrape: "true"` annotation

**Storage**: Local storage (ephemeral, data lost on pod restart)

### Grafana

**Purpose**: Visualize Prometheus metrics

**Features**:
- Auto-configured Prometheus data source
- Persistent storage via EFS (AWS) or local-path (kind)
- All dashboards, settings, and users persist across pod restarts

**Credentials**: Set at deploy time via `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD`

## Access Monitoring Tools

### Prometheus

```bash
# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node prometheus

# Open browser: http://localhost:30900
```

### Grafana

```bash
# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node grafana

# Open browser: http://localhost:30300
# Login with the credentials configured at deploy time
```

## Prometheus Queries

### Host Metrics

**CPU Usage**:
```promql
node_cpu_seconds_total
100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

**Memory Usage**:
```promql
node_memory_MemAvailable_bytes
node_memory_MemTotal_bytes
100 - ((node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100)
```

**Disk I/O**:
```promql
node_disk_io_time_seconds_total
rate(node_disk_read_bytes_total[5m])
rate(node_disk_written_bytes_total[5m])
```

**Network Traffic**:
```promql
node_network_receive_bytes_total
node_network_transmit_bytes_total
rate(node_network_receive_bytes_total{device!~"lo|veth.*"}[5m])
rate(node_network_transmit_bytes_total{device!~"lo|veth.*"}[5m])
```

**Disk Usage**:
```promql
node_filesystem_avail_bytes{mountpoint="/"}
node_filesystem_size_bytes{mountpoint="/"}
100 - ((node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100)
```

### Kubernetes Metrics

**Pod Status**:
```promql
kube_pod_status_phase
sum(kube_pod_status_phase{phase="Running"}) by (namespace)
count(kube_pod_status_phase{phase="Failed"})
```

**Deployment Replicas**:
```promql
kube_deployment_status_replicas_available
kube_deployment_spec_replicas
```

**Pod Restarts**:
```promql
kube_pod_container_status_restarts_total
sum(kube_pod_container_status_restarts_total) by (namespace, pod)
```

**Node Capacity**:
```promql
kube_node_status_capacity
kube_node_status_allocatable
```

**Container Resources**:
```promql
kube_pod_container_resource_requests{resource="cpu"}
kube_pod_container_resource_limits{resource="memory"}
sum(kube_pod_container_resource_requests{resource="cpu"}) by (namespace, pod)
```

## Grafana Dashboards

### Import Pre-built Dashboards

#### Essential Dashboards

**Node Exporter Full (ID: 1860)**:
1. Click "Dashboards" → "Import"
2. Enter dashboard ID: `1860`
3. Click "Load"
4. Select Prometheus data source
5. Click "Import"

Includes:
- CPU usage (per core and total)
- Memory usage and available
- Disk I/O and space
- Network traffic
- System load
- Filesystem statistics

**Kubernetes Cluster Monitoring (ID: 15172)**:
1. Click "Dashboards" → "Import"
2. Enter dashboard ID: `15172`
3. Select Prometheus data source
4. Click "Import"

Includes:
- Cluster overview
- Node status and resources
- Pod metrics by namespace
- Deployment status

#### Optional Dashboards

- **13332**: Kube State Metrics v2
- **315**: Kubernetes Cluster Monitoring
- **6417**: Kubernetes Cluster (Prometheus)
- **15661**: Kubernetes Deployments
- **8588**: Kubernetes Deployment Statefulset Daemonset metrics

### Create Custom Dashboard

#### CPU Usage Panel

```promql
100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

**Visualization**: Gauge or Time series

#### Memory Usage Panel

```promql
100 - ((node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100)
```

**Visualization**: Gauge or Time series

#### Disk Usage Panel

```promql
100 - ((node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) * 100)
```

**Visualization**: Gauge

#### Network Traffic Panel

Received:
```promql
rate(node_network_receive_bytes_total{device!~"lo|veth.*"}[5m])
```

Transmitted:
```promql
rate(node_network_transmit_bytes_total{device!~"lo|veth.*"}[5m])
```

**Visualization**: Time series

#### Pod Status Panel

```promql
sum(kube_pod_status_phase{phase="Running"}) by (namespace)
```

**Visualization**: Bar chart or Table

### Dashboard Variables

Use variables for dynamic filtering:

**Namespace Variable**:
- Name: `namespace`
- Type: Query
- Query: `label_values(kube_pod_info, namespace)`

**Pod Variable**:
- Name: `pod`
- Type: Query
- Query: `label_values(kube_pod_info{namespace="$namespace"}, pod)`

Use in queries:
```promql
kube_pod_status_phase{namespace="$namespace", pod=~"$pod"}
```

## Alerts (Future Enhancement)

Prometheus supports alerting via Alertmanager. To add alerts:

1. Deploy Alertmanager
2. Configure alert rules in Prometheus
3. Set up notification channels (Slack, email, etc.)

**Example Alert Rule**:

```yaml
groups:
- name: example
  rules:
  - alert: HighMemoryUsage
    expr: (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100 < 10
    for: 5m
    annotations:
      summary: "High memory usage detected"
```

## Troubleshooting

### Prometheus Not Scraping Targets

Check Prometheus targets page:
- Open http://localhost:30900/targets
- Look for endpoints in "DOWN" state

Check service discovery:
- Verify ServiceMonitor or scrape configs
- Check RBAC permissions

### Grafana Dashboard Not Loading

Check Prometheus data source:
1. Go to "Connections" → "Data sources"
2. Click "Prometheus"
3. Click "Save & test"

If error, verify Prometheus is accessible from Grafana pod.

### Metrics Not Available

Check if the exporter is running:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -l app=node-exporter'
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get pods -l app=kube-state-metrics -n kube-system'
```

Check exporter logs:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs -l app=node-exporter --tail=50'
```

## Best Practices

1. **Use Time Ranges**: Always specify time ranges in queries (e.g., `[5m]`)
2. **Aggregate Data**: Use `sum`, `avg`, `max` for meaningful insights
3. **Filter Noise**: Exclude irrelevant labels (e.g., `device!~"lo|veth.*"`)
4. **Set Up Alerts**: Define alerts for critical metrics
5. **Regular Cleanup**: Delete unused dashboards and queries
6. **Monitor Storage**: Prometheus storage can grow large over time
