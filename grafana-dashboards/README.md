# Grafana Dashboards

This directory contains pre-downloaded Grafana dashboard JSON files for offline import.

## Available Dashboards

### 1860-node-exporter-full.json
- **Dashboard ID:** 1860
- **Name:** Node Exporter Full
- **Purpose:** Host machine metrics monitoring
- **Metrics:**
  - CPU usage (per core and total)
  - Memory usage and available
  - Disk I/O and space
  - Network traffic
  - System load
  - Filesystem statistics

### 15172-kubernetes-monitoring.json
- **Dashboard ID:** 15172
- **Name:** Kubernetes Cluster Monitoring (via Prometheus)
- **Purpose:** Kubernetes cluster, deployments, and pods monitoring
- **Metrics:**
  - Deployment status and pod counts
  - Pod phases (Running/Pending/Failed)
  - Container restart counts
  - Resource usage by namespace

### 13332-kube-state-metrics.json
- **Dashboard ID:** 13332
- **Name:** Kube State Metrics v2
- **Purpose:** Detailed Kubernetes object state metrics
- **Metrics:**
  - Deployment details
  - StatefulSets, DaemonSets
  - PersistentVolume usage
  - Comprehensive Kubernetes metrics

## How to Import

### Method 1: Import from grafana.com (Online)

1. Open Grafana: http://localhost:30300
2. Click "Dashboards" → "Import"
3. Enter dashboard ID (e.g., `1860`)
4. Click "Load"
5. Select Prometheus data source
6. Click "Import"

### Method 2: Import JSON file (Offline)

If Method 1 fails with gateway timeout:

1. Open Grafana: http://localhost:30300
2. Click "Dashboards" → "Import"
3. Click "Upload JSON file"
4. Select one of the JSON files from this directory
5. Select Prometheus data source
6. Click "Import"

### Method 3: Copy-Paste JSON (Alternative)

1. Open Grafana: http://localhost:30300
2. Click "Dashboards" → "Import"
3. Open the JSON file in a text editor
4. Copy the entire JSON content
5. Paste into "Import via panel json" text area
6. Click "Load"
7. Select Prometheus data source
8. Click "Import"

## Recommended Import Order

1. **1860-node-exporter-full.json** - Start with host metrics
2. **15172-kubernetes-monitoring.json** - Then add Kubernetes cluster overview
3. **13332-kube-state-metrics.json** - Add detailed Kubernetes metrics (optional)

## Updating Dashboards

To download the latest versions:

```bash
curl -s https://grafana.com/api/dashboards/1860/revisions/latest/download -o grafana-dashboards/1860-node-exporter-full.json
curl -s https://grafana.com/api/dashboards/15172/revisions/latest/download -o grafana-dashboards/15172-kubernetes-monitoring.json
curl -s https://grafana.com/api/dashboards/13332/revisions/latest/download -o grafana-dashboards/13332-kube-state-metrics.json
```
