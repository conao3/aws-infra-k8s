# Security

## Overview

This document describes the security measures implemented in the aws-infra-k8s project.

## Security Improvements from Review

### 1. Kubernetes Dashboard Permission Restriction

**Issue**: Dashboard had cluster-admin permissions with write access to the entire cluster.

**Improvement**: Changed ClusterRoleBinding from `cluster-admin` to `view`, restricting to read-only access.

**Configuration file**: `k8s/kubernetes-dashboard/admin-user.yaml`

```yaml
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: view  # Previously cluster-admin
```

### 2. NetworkPolicy Implementation

**Issue**: No communication restrictions between Pods; all Pods could communicate with each other.

**Improvement**: Implemented NetworkPolicy for all applications and services, allowing only minimum necessary communication.

See [011-network-policy.md](./011-network-policy.md) for details.

### 3. EFS IAM Permission Restriction

**Issue**: EFS CSI Driver IAM permissions had Resource set to `"*"`.

**Improvement**: Restricted Resource to a specific EFS ARN, allowing access only to the designated EFS filesystem.

**Configuration file**: `src/conao3/aws_infra_k8s/cluster.clj`

```clojure
:Resource {"Fn::Sub" "arn:aws:elasticfilesystem:${AWS::Region}:${AWS::AccountId}:file-system/${EfsFileSystemId}"}
```

## Implemented Security Measures

### Network Security

#### ALB
- **CloudFront-only access**: SecurityGroup allows access only from CloudFront IP ranges
- **Configuration**: `src/conao3/aws_infra_k8s/alb.clj`

#### EC2
- **Private subnet placement**: Instances in private subnet, not directly accessible from internet
- **SSM Session Manager**: Access without opening SSH port
- **Configuration**: `src/conao3/aws_infra_k8s/cluster.clj`

#### WAF
- **Applied to CloudFront**: Blocks malicious traffic
- **Configuration**: `src/conao3/aws_infra_k8s/waf.clj`

### Instance Security

#### IMDSv2 Enforcement
- **Metadata Service v2 required**: Prevents IMDSv1 vulnerabilities
- **Configuration**: `src/conao3/aws_infra_k8s/cluster.clj`

```clojure
:MetadataOptions
{:HttpTokens "required"
 :HttpPutResponseHopLimit 2
 :HttpEndpoint "enabled"}
```

#### EBS Volumes
- **Encryption**: Encrypted by default (AWS account setting)
- **Delete on termination**: EBS automatically deleted with instance
- **Minimum size**: 10GB (minimized according to usage)

### IAM Security

#### IAM Role
- **Principle of least privilege**: Only necessary permissions granted
- **Managed policies**:
  - AmazonSSMManagedInstanceCore (SSM connection)
  - CloudWatchAgentServerPolicy (logs/metrics)
  - AmazonEC2ContainerRegistryReadOnly (container images)
- **Custom policies**:
  - EFS CSI Driver (access only to specific EFS)

### Kubernetes Security

#### RBAC
- **Dashboard**: view role only (read-only)
- **Prometheus**: cluster metrics read permission
- **EFS CSI Driver**: minimum necessary storage operations
- **kube-state-metrics**: Kubernetes resource read permission

#### NetworkPolicy
- **Pod-to-pod communication restriction**: Allow only necessary traffic
- **Details**: [011-network-policy.md](./011-network-policy.md)

#### Service Account
- **Dedicated Service Account per component**: Permission separation

### SSL/TLS

#### CloudFront
- **ACM certificate**: *.sancode.dev wildcard certificate
- **TLS 1.2+**: Older protocols disabled

#### k3s
- **Internal communication encryption**: k3s automatically generates and manages TLS certificates

### Storage Security

#### EFS
- **Encryption**: Encryption at rest
- **Access control**: IAM role-based access control
- **Mount targets**: Private subnets only

## Not Implemented / Future Considerations

### Medium Priority

#### PodSecurityStandards
- **Current**: Not implemented
- **Recommended**: Apply Baseline or Restricted policy
- **Impact**: Adds security validation at Pod startup

### Low Priority

#### Grafana Authentication
- **Current**: Default authentication (admin/admin)
- **Recommended**: Change to strong password or integrate external authentication
- **Impact**: Currently accessible only via CloudFront, so lower priority

#### Secrets Management
- **Current**: Using ConfigMap
- **Recommended**: Use Kubernetes Secrets or external Secrets Manager (AWS Secrets Manager, HashiCorp Vault)
- **Impact**: Secure management of sensitive information

#### Audit Logs
- **Current**: Basic logs only
- **Recommended**: Enable Kubernetes audit logs and send to CloudWatch Logs
- **Impact**: Improved security incident detection and investigation capabilities

## Security Checklist

### Before Deployment

- [ ] All NetworkPolicies applied
- [ ] IAM permissions follow least privilege
- [ ] SecurityGroups properly configured
- [ ] Kubernetes Dashboard permissions restricted

### Regular Review

- [ ] Review SecurityGroup rules
- [ ] Review IAM permissions
- [ ] Review NetworkPolicies
- [ ] Delete unnecessary Pods, Services, ConfigMaps
- [ ] Optimize EBS volume sizes

### Incident Response

1. **Detect suspicious activity**:
   ```bash
   kubectl logs -f deployment/<pod-name>
   aws cloudwatch logs tail /aws/ec2/<instance-id>
   ```

2. **Temporarily disable NetworkPolicy**:
   ```bash
   kubectl delete networkpolicy --all
   ```

3. **Emergency Pod stop**:
   ```bash
   kubectl delete pod <pod-name>
   ```

4. **Isolate cluster**: Remove all inbound rules from SecurityGroup

## References

- [Kubernetes Security Best Practices](https://kubernetes.io/docs/concepts/security/security-checklist/)
- [AWS Security Best Practices](https://aws.amazon.com/architecture/security-identity-compliance/)
- [k3s Security Considerations](https://docs.k3s.io/security/hardening-guide)
