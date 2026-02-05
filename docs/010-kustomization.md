# Kustomization-based Deployment

This document describes the Kustomization-based deployment configuration used in this project.

## Overview

All Kubernetes resources are managed using Kustomize, which provides:
- **Declarative resource ordering**: No need to manually order resource application
- **Single command deployment**: Deploy all resources with one command
- **Modular structure**: Each component has its own kustomization.yaml
- **Template-free configuration**: Uses native Kubernetes manifests with overlays

## Directory Structure

```
k8s/
├── kustomization.yaml                    # Root kustomization (aggregates all components)
├── aws-efs-csi-driver/
│   ├── kustomization.yaml               # EFS CSI Driver resources
│   ├── service-account.yaml
│   ├── rbac.yaml
│   ├── csidriver.yaml
│   ├── storageclass.yaml
│   ├── controller.yaml
│   └── node.yaml
├── traefik/
│   ├── kustomization.yaml               # Traefik Ingress Controller
│   ├── namespace.yaml
│   ├── rbac.yaml
│   ├── service.yaml
│   ├── ingressclass.yaml
│   ├── deployment.yaml
│   └── healthcheck-ingress.yaml
├── prometheus/
│   ├── kustomization.yaml               # Prometheus monitoring
│   ├── rbac.yaml
│   ├── configmap.yaml
│   ├── service.yaml
│   └── deployment.yaml
├── grafana/
│   ├── kustomization.yaml               # Grafana dashboards
│   ├── pvc-efs.yaml
│   ├── datasource-configmap.yaml
│   ├── service.yaml
│   └── deployment.yaml
├── node-exporter/
│   ├── kustomization.yaml               # Host metrics exporter
│   ├── service.yaml
│   └── daemonset.yaml
├── kube-state-metrics/
│   ├── kustomization.yaml               # K8s object metrics
│   ├── service-account.yaml
│   ├── cluster-role.yaml
│   ├── cluster-role-binding.yaml
│   ├── service.yaml
│   └── deployment.yaml
├── kubernetes-dashboard/
│   ├── kustomization.yaml               # Kubernetes Dashboard (with remote base)
│   ├── admin-user.yaml
│   └── service-patch.yaml
├── app1/
│   ├── kustomization.yaml               # Sample application 1
│   ├── service.yaml
│   ├── deployment.yaml
│   └── ingress.yaml
├── app2/
│   └── ...                              # Sample application 2
└── app3/
    └── ...                              # Sample application 3
```

## Root Kustomization

The root `k8s/kustomization.yaml` aggregates all component kustomizations:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - aws-efs-csi-driver
  - traefik
  - prometheus
  - grafana
  - node-exporter
  - kube-state-metrics
  - kubernetes-dashboard
  - app1
  - app2
  - app3
```

## Component Kustomizations

Each component directory contains its own `kustomization.yaml` that lists resources in the correct order:

### Example: Traefik

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - namespace.yaml           # 1. Create namespace first
  - rbac.yaml               # 2. Setup RBAC
  - service.yaml            # 3. Create service
  - ingressclass.yaml       # 4. Create IngressClass
  - deployment.yaml         # 5. Deploy application
  - healthcheck-ingress.yaml  # 6. Create Ingress
```

### Example: AWS EFS CSI Driver

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - service-account.yaml    # 1. Create ServiceAccount
  - rbac.yaml              # 2. Setup RBAC
  - csidriver.yaml         # 3. Register CSI Driver
  - storageclass.yaml      # 4. Create StorageClass
  - controller.yaml        # 5. Deploy controller
  - node.yaml             # 6. Deploy node DaemonSet
```

## Deployment

### Deploy All Resources

Single command to deploy all Kubernetes resources:

```bash
kubectl apply -k k8s/
```

Or using the deployment script:

```bash
AWS_PROFILE=conao3.k8s bin/k8s/deploy
```

The script:
1. Finds healthy EC2 instance in AutoScaling Group
2. Copies all manifests to the instance
3. Substitutes EFS_FILE_SYSTEM_ID in storageclass.yaml
4. Runs `kubectl apply -k /tmp/k8s-manifests/`

### Deploy Specific Component

Deploy only one component:

```bash
kubectl apply -k k8s/traefik/
kubectl apply -k k8s/prometheus/
kubectl apply -k k8s/app1/
```

## Benefits

### 1. Dependency Management

Kustomize automatically orders resources based on type:
- Namespaces are created first
- RBAC resources (ServiceAccounts, Roles, RoleBindings) are created before Deployments
- CustomResourceDefinitions are created before custom resources

No need to manually order `kubectl apply` commands.

### 2. Single Command Deployment

Instead of multiple commands:

```bash
# Old way (order-dependent)
kubectl apply -f traefik/namespace.yaml
kubectl apply -f traefik/rbac.yaml
kubectl apply -f traefik/service.yaml
kubectl apply -f traefik/deployment.yaml
kubectl apply -f app1/
kubectl apply -f app2/
# ... many more commands
```

Now just one command:

```bash
# New way (order-independent)
kubectl apply -k k8s/
```

### 3. Modular Structure

Each component is self-contained with its own `kustomization.yaml`. Easy to:
- Add new components
- Remove components
- Reorder components
- Test components individually

### 4. No Template Bloat

Uses native Kubernetes YAML manifests without introducing templating DSL (unlike Helm).

## Adding New Application

To add a new application:

1. Create directory:

```bash
mkdir k8s/my-app
```

2. Add manifests (deployment.yaml, service.yaml, etc.)

3. Create kustomization.yaml:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - service.yaml
  - deployment.yaml
  - ingress.yaml
```

4. Add to root kustomization:

```yaml
# k8s/kustomization.yaml
resources:
  - ...
  - my-app  # Add this line
```

5. Deploy:

```bash
AWS_PROFILE=conao3.k8s bin/k8s/deploy
```

## Variable Substitution

Some values need to be substituted at deployment time (e.g., EFS File System ID).

The deployment script handles this:

```bash
# In bin/k8s/deploy
sed -i 's/${EFS_FILE_SYSTEM_ID}/${EFS_FILE_SYSTEM_ID}/g' \
  /tmp/k8s-manifests/aws-efs-csi-driver/storageclass.yaml
```

The StorageClass uses placeholder:

```yaml
# k8s/aws-efs-csi-driver/storageclass.yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: efs-sc
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap
  fileSystemId: ${EFS_FILE_SYSTEM_ID}  # Replaced at deployment
  directoryPerms: "700"
```

## Kubernetes Dashboard Special Case

The Kubernetes Dashboard uses a remote base:

```yaml
# k8s/kubernetes-dashboard/kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
- https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
- admin-user.yaml
patchesStrategicMerge:
- service-patch.yaml
```

This demonstrates Kustomize's ability to:
- Reference remote resources
- Apply local patches to remote resources
- Add additional resources

## Troubleshooting

### Dry Run

Preview changes without applying:

```bash
kubectl apply -k k8s/ --dry-run=client -o yaml
```

### View Rendered Manifests

See what Kustomize generates:

```bash
kubectl kustomize k8s/
```

### Validate Structure

Validate kustomization files:

```bash
kubectl kustomize k8s/ > /dev/null
echo "Kustomization is valid"
```

## References

- [Kustomize Documentation](https://kustomize.io/)
- [Kubernetes Kustomize Guide](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
