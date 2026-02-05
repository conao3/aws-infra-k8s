# Persistent Storage with EFS

This project uses AWS Elastic File System (EFS) One Zone with the AWS EFS CSI Driver for persistent storage in Kubernetes.

## Features

- **EFS One Zone**: Cost-effective single-AZ storage (~$0.19/GB/month, about 47% cheaper than Standard)
- **ReadWriteMany**: Multiple pods can read and write to the same volume simultaneously
- **Automatic Provisioning**: StorageClass automatically creates EFS Access Points
- **Single-AZ Deployment**: EFS in ap-northeast-1a (same AZ as cluster)
- **Encrypted**: EFS file system is encrypted at rest
- **Bursting Performance**: Throughput scales with file system size

## Architecture

```
Kubernetes Pod
  ↓ (mount)
EFS CSI Driver (DaemonSet on each node)
  ↓ (NFS mount)
EFS Mount Target (ap-northeast-1a)
  ↓
EFS One Zone File System (ap-northeast-1a)
```

## Cost Comparison

**EFS Pricing in ap-northeast-1:**
- EFS Standard: ~$0.36/GB/month (multi-AZ replication)
- EFS One Zone: ~$0.19/GB/month (single-AZ, 47% cheaper)

For a single-node cluster in one AZ, EFS One Zone provides significant cost savings without sacrificing functionality.

## Usage

### Create PersistentVolumeClaim

Use EFS in your application by creating a PVC:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: my-app-storage
  namespace: default
spec:
  accessModes:
  - ReadWriteMany
  storageClassName: efs-sc
  resources:
    requests:
      storage: 10Gi
```

The EFS CSI Driver automatically creates an EFS Access Point for each PVC.

### Mount in Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  template:
    spec:
      containers:
      - name: app
        image: myapp:latest
        volumeMounts:
        - name: data
          mountPath: /data
      volumes:
      - name: data
        persistentVolumeClaim:
          claimName: my-app-storage
```

### Example: Grafana

Grafana uses EFS for data persistence:

```yaml
# k8s/grafana/pvc-efs.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: grafana-storage-efs
  namespace: default
spec:
  accessModes:
  - ReadWriteMany
  storageClassName: efs-sc
  resources:
    requests:
      storage: 5Gi
```

## AWS EFS CSI Driver

### Components

The EFS CSI Driver consists of:

1. **Controller**: Manages EFS Access Points
   - Creates Access Points for PVCs
   - Deletes Access Points when PVCs are deleted

2. **Node DaemonSet**: Mounts EFS on nodes
   - Runs on every node
   - Mounts EFS filesystems to pods

3. **StorageClass**: Defines provisioning behavior

### StorageClass Configuration

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: efs-sc
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap
  fileSystemId: fs-xxxxx
  directoryPerms: "700"
```

The `fileSystemId` is automatically set during deployment using the EFS ID from CloudFormation exports.

## Access Modes

EFS supports multiple access modes:

| Access Mode | Support | Description |
|-------------|---------|-------------|
| ReadWriteOnce (RWO) | ✅ | Single pod read/write |
| ReadOnlyMany (ROX) | ✅ | Multiple pods read-only |
| ReadWriteMany (RWX) | ✅ | Multiple pods read/write |

**Use Case**: RWX is ideal for:
- Shared configuration files
- Shared data between pods
- Multi-replica deployments with shared storage

## Performance

### Throughput

EFS provides bursting throughput that scales with file system size:

| File System Size | Baseline Throughput | Burst Throughput |
|------------------|---------------------|------------------|
| 1 GB | 50 KB/s | 100 MB/s |
| 10 GB | 500 KB/s | 100 MB/s |
| 100 GB | 5 MB/s | 100 MB/s |
| 1 TB | 50 MB/s | 300 MB/s |

For small workloads (<1GB), bursting provides sufficient performance.

### Latency

- First-byte latency: Low single-digit milliseconds
- Operations latency: Low single-digit milliseconds

Suitable for most application workloads, but not for latency-sensitive databases.

## Monitoring

### Check EFS Usage

```bash
AWS_PROFILE=conao3.k8s aws efs describe-file-systems \
  --file-system-id $(aws cloudformation list-exports \
    --query "Exports[?Name=='dev-k8s-EfsFileSystemId'].Value" \
    --output text \
    --profile conao3.k8s) \
  --query 'FileSystems[0].SizeInBytes' \
  --profile conao3.k8s
```

### Check Access Points

```bash
AWS_PROFILE=conao3.k8s aws efs describe-access-points \
  --file-system-id $(aws cloudformation list-exports \
    --query "Exports[?Name=='dev-k8s-EfsFileSystemId'].Value" \
    --output text \
    --profile conao3.k8s) \
  --profile conao3.k8s
```

### CloudWatch Metrics

EFS provides CloudWatch metrics:
- `ClientConnections`: Number of connections to the file system
- `DataReadIOBytes`: Bytes read
- `DataWriteIOBytes`: Bytes written
- `TotalIOBytes`: Total bytes transferred
- `PercentIOLimit`: Percentage of I/O limit used

## Troubleshooting

### PVC Stuck in Pending

Check EFS CSI controller logs:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login \
  'kubectl logs -l app=efs-csi-controller -n default --tail=50'
```

Common issues:
- EFS mount target not available in the AZ
- Security group blocking NFS traffic (port 2049)
- Incorrect file system ID in StorageClass

### Mount Failed

Check EFS CSI node logs:

```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login \
  'kubectl logs -l app=efs-csi-node -n default --tail=50'
```

### Access Denied

Verify EFS mount target security group allows NFS from EC2 security group:

```bash
AWS_PROFILE=conao3.k8s aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=dev-k8s-efs*" \
  --query 'SecurityGroups[0].IpPermissions' \
  --profile conao3.k8s
```

## Cleanup

When deleting a PVC, the associated EFS Access Point is automatically deleted.

To manually delete an Access Point:

```bash
AWS_PROFILE=conao3.k8s aws efs delete-access-point \
  --access-point-id fsap-xxxxx \
  --profile conao3.k8s
```

## Alternatives

### Local Storage (hostPath)

**Pros**:
- Low latency
- Free

**Cons**:
- Not persistent across node replacements
- Not shared between pods on different nodes

**Use Case**: Temporary cache, logs

### EBS (via EBS CSI Driver)

**Pros**:
- Lower latency than EFS
- Better for databases

**Cons**:
- Only ReadWriteOnce (single pod)
- More expensive per GB
- Requires provisioning per pod

**Use Case**: Databases, single-pod stateful applications

### S3 (via mountpoint-s3)

**Pros**:
- Very cheap ($0.023/GB/month)
- Unlimited scalability

**Cons**:
- Higher latency
- Eventually consistent
- Limited POSIX compatibility

**Use Case**: Large files, archives, media storage

## Best Practices

1. **Right-size storage requests**: EFS bills for actual usage, not requested size
2. **Use EFS for shared data**: For single-pod workloads, consider EBS
3. **Monitor usage**: Set CloudWatch alarms for unexpected growth
4. **Clean up unused PVCs**: Delete PVCs when no longer needed
5. **Use lifecycle policies**: Configure EFS lifecycle management for cost savings
