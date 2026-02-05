# Network Policy

## Overview

Kubernetes NetworkPolicy is used to control communication between Pods, blocking unnecessary traffic and improving security.

## NetworkPolicy Support in k3s

k3s supports NetworkPolicy by default. It uses a built-in Network Policy Controller (based on kube-router) that works without additional configuration.

### References
- [Network Policies in K3s | SUSE Communities](https://www.suse.com/c/rancher_blog/k3s-network-policy/)
- [Basic Network Options | K3s](https://docs.k3s.io/networking/basic-network-options)

## Implemented NetworkPolicies

### Applications (app1, app2, app3)

Each application has the same NetworkPolicy applied.

**Allowed traffic:**
- **Ingress**:
  - From Traefik namespace (`kubernetes.io/metadata.name=traefik`) with `app=traefik` Pod on port 80
  - From `app=prometheus` Pod in the same namespace on port 80
- **Egress**:
  - DNS to kube-system namespace (`kubernetes.io/metadata.name=kube-system`) on port 53 (TCP/UDP)

**Configuration file:** `k8s/app{1,2,3}/network-policy.yaml`

### Traefik

As an Ingress Controller, it accepts connections from external sources and routes them to applications.

**Allowed traffic:**
- **Ingress**:
  - From all namespaces on port 80
- **Egress**:
  - To all Pods in default namespace (for routing to applications)
  - To kube-system namespace for DNS (port 53 TCP/UDP) and Kubernetes API (port 443)

**Configuration file:** `k8s/traefik/network-policy.yaml`

### Prometheus

Accesses each application, node-exporter, and kube-state-metrics for metrics collection.

**Allowed traffic:**
- **Ingress**:
  - From `app=grafana` Pod in the same namespace on port 9090
- **Egress**:
  - To `app=app1`, `app=app2`, `app=app3` on port 80 (application metrics collection)
  - To `app=node-exporter` on port 9100
  - To `app=kube-state-metrics` on port 8080
  - To kube-system namespace for DNS (port 53 TCP/UDP), Kubernetes API (port 443), and kubelet (port 10250)

**Configuration file:** `k8s/prometheus/network-policy.yaml`

### Grafana

As a visualization tool, it retrieves data from Prometheus and accepts external access.

**Allowed traffic:**
- **Ingress**:
  - From Traefik namespace (`kubernetes.io/metadata.name=traefik`) with `app=traefik` Pod on port 3000
- **Egress**:
  - To `app=prometheus` Pod in the same namespace on port 9090
  - To kube-system namespace for DNS (port 53 TCP/UDP)
  - To all namespaces on port 2049 (EFS/NFS)

**Configuration file:** `k8s/grafana/network-policy.yaml`

### node-exporter

Provides node metrics to Prometheus.

**Allowed traffic:**
- **Ingress**:
  - From `app=prometheus` Pod in the same namespace on port 9100

**Configuration file:** `k8s/node-exporter/network-policy.yaml`

### kube-state-metrics

Provides Kubernetes resource state as metrics to Prometheus.

**Allowed traffic:**
- **Ingress**:
  - From `app=prometheus` Pod in the same namespace on port 8080
- **Egress**:
  - To kube-system namespace for DNS (port 53 TCP/UDP) and Kubernetes API (port 443)

**Configuration file:** `k8s/kube-state-metrics/network-policy.yaml`

## Traffic Flow Diagram

```
[Internet/CloudFront]
    ↓
[Traefik (traefik namespace)]
    ↓ (port 80)
[app1/app2/app3 (default namespace)]
    ↑ (port 80)
[Prometheus (default namespace)]
    ↓ (port 9090)
[Grafana (default namespace)]
    ↑ (port 3000)
[Traefik] → [External Access]

[Prometheus] → [node-exporter] (port 9100)
[Prometheus] → [kube-state-metrics] (port 8080)
```

## Examples of Blocked Traffic

NetworkPolicy blocks unnecessary traffic such as:

- app1 → app2, app3
- app2 → app1, app3
- app3 → app1, app2
- Grafana → app1/app2/app3 (direct)
- node-exporter → all other Pods
- kube-state-metrics → anything except Kubernetes API

## Verification

### Check application access

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://app1.sancode.dev/index.html
curl -s -o /dev/null -w "%{http_code}\n" https://app2.sancode.dev/index.html
curl -s -o /dev/null -w "%{http_code}\n" https://app3.sancode.dev/index.html
```

All should return `200`.

### List NetworkPolicies

```bash
kubectl get networkpolicy
kubectl get networkpolicy -n traefik
```

### Check Prometheus access (allowed)

```bash
kubectl exec deployment/prometheus -- wget -q -O - http://app1/index.html
```

Should succeed.

### Check inter-app communication (blocked)

```bash
kubectl exec deployment/app2 -- timeout 3 curl -s http://app1/index.html
```

Should timeout or error.

## Troubleshooting

### Pods not starting or unable to communicate

1. Check NetworkPolicy configuration:
```bash
kubectl describe networkpolicy <policy-name>
```

2. Check Pod labels:
```bash
kubectl get pod --show-labels
```

3. Check Namespace labels:
```bash
kubectl get namespace --show-labels
```

### DNS resolution fails

All Pods need an Egress rule for DNS (port 53 TCP/UDP) to kube-system namespace.

### Cannot access Kubernetes API

Add an Egress rule for port 443 to kube-system namespace.

## Disabling NetworkPolicy

If issues occur, temporarily remove NetworkPolicies:

```bash
kubectl delete networkpolicy --all
kubectl delete networkpolicy --all -n traefik
```

To re-enable:

```bash
AWS_PROFILE=conao3.k8s AWS_REGION=ap-northeast-1 bin/k8s deploy
```

## Security Best Practices

1. **Principle of least privilege**: Allow only minimum necessary traffic
2. **Namespace isolation**: Place critical components in dedicated namespaces
3. **Allow DNS resolution**: Grant all Pods DNS resolution access (kube-system:53)
4. **Regular review**: Update NetworkPolicies as applications change
