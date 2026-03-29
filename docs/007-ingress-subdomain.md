# Ingress and Subdomain Setup

This document describes how to configure Traefik Ingress Controller for multi-subdomain routing.

## Architecture

```
Internet → CloudFront → ALB → Traefik (NodePort 30080) → Ingress Rules → Services → Pods
```

**Routing Example**:
- `app1.example.com` → app1 service
- `app2.example.com` → app2 service
- `app3.example.com` → app3 service

Traefik automatically discovers Ingress resources and configures routing based on the `host` field.

## Ingress Resources

### Example Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: app1
  namespace: default
spec:
  ingressClassName: traefik
  rules:
  - host: app1.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: app1
            port:
              number: 80
```

### Multiple Paths

```yaml
spec:
  rules:
  - host: app.example.com
    http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: api-service
            port:
              number: 8080
      - path: /
        pathType: Prefix
        backend:
          service:
            name: web-service
            port:
              number: 80
```

## Multi-Subdomain Setup

To use custom domains with CloudFront, follow these steps:

### 1. Create ACM Certificate

Create a wildcard certificate in **us-east-1** (required for CloudFront):

```bash
aws acm request-certificate \
  --domain-name "*.example.com" \
  --validation-method DNS \
  --region us-east-1 \
  --profile conao3.k8s
```

Get the certificate ARN:

```bash
aws acm list-certificates --region us-east-1 --profile conao3.k8s
```

Add the DNS validation record to Route53 and wait for validation to complete.

### 2. Configure Route53

Create A records for each subdomain pointing to CloudFront:

```bash
DISTRIBUTION_DOMAIN=$(aws cloudformation describe-stacks \
  --stack-name dev-k8s-cloudfront \
  --region us-east-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`DistributionDomainName`].OutputValue' \
  --output text \
  --profile conao3.k8s)

aws route53 change-resource-record-sets \
  --hosted-zone-id ZXXXXXXXXXXXXX \
  --change-batch '{
    "Changes": [
      {
        "Action": "CREATE",
        "ResourceRecordSet": {
          "Name": "app1.example.com",
          "Type": "A",
          "AliasTarget": {
            "HostedZoneId": "Z2FDTNDATAQYW2",
            "DNSName": "'${DISTRIBUTION_DOMAIN}'",
            "EvaluateTargetHealth": false
          }
        }
      },
      {
        "Action": "CREATE",
        "ResourceRecordSet": {
          "Name": "app2.example.com",
          "Type": "A",
          "AliasTarget": {
            "HostedZoneId": "Z2FDTNDATAQYW2",
            "DNSName": "'${DISTRIBUTION_DOMAIN}'",
            "EvaluateTargetHealth": false
          }
        }
      },
      {
        "Action": "CREATE",
        "ResourceRecordSet": {
          "Name": "app3.example.com",
          "Type": "A",
          "AliasTarget": {
            "HostedZoneId": "Z2FDTNDATAQYW2",
            "DNSName": "'${DISTRIBUTION_DOMAIN}'",
            "EvaluateTargetHealth": false
          }
        }
      }
    ]
  }' \
  --profile conao3.k8s
```

**Note**: `Z2FDTNDATAQYW2` is the CloudFront hosted zone ID (constant for all CloudFront distributions).

### 3. Update CloudFront Distribution

Add the ACM certificate and alternate domain names to CloudFront.

#### Option A: Manual (AWS Console)

1. Go to CloudFront console
2. Select your distribution
3. Click "Edit"
4. Add alternate domain names: `app1.example.com`, `app2.example.com`, `app3.example.com`
5. Select the ACM certificate created in step 1
6. Save changes

#### Option B: Automated (Update cloudfront.clj)

Edit `src/conao3/aws_infra_k8s/cloudfront.clj` and add:

```clojure
:DistributionConfig
{
 ;; ... existing config ...
 :ViewerCertificate
 {:AcmCertificateArn "arn:aws:acm:us-east-1:ACCOUNT_ID:certificate/CERT_ID"
  :SslSupportMethod "sni-only"
  :MinimumProtocolVersion "TLSv1.2_2021"}
 :Aliases ["app1.example.com" "app2.example.com" "app3.example.com"]
}
```

Then redeploy:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy cloudfront
```

### 4. Deploy Applications

```bash
AWS_PROFILE=conao3.k8s bin/k8s deploy
```

### 5. Verify

```bash
curl https://app1.example.com
curl https://app2.example.com
curl https://app3.example.com
```

## Add New Application

To add a new application with a subdomain:

### 1. Create Manifests

Create `k8s/my-app/` with the following files:

**deployment.yaml**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  replicas: 1
  selector:
    matchLabels:
      app: my-app
  template:
    metadata:
      labels:
        app: my-app
    spec:
      containers:
      - name: nginx
        image: nginx:1.28.3-alpine
        ports:
        - containerPort: 80
```

**service.yaml**:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app
spec:
  type: ClusterIP
  selector:
    app: my-app
  ports:
  - port: 80
    targetPort: 80
```

**ingress.yaml**:
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-app
spec:
  ingressClassName: traefik
  rules:
  - host: my-app.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: my-app
            port:
              number: 80
```

### 2. Add Route53 Record

```bash
DISTRIBUTION_DOMAIN=$(aws cloudformation describe-stacks \
  --stack-name dev-k8s-cloudfront \
  --region us-east-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`DistributionDomainName`].OutputValue' \
  --output text \
  --profile conao3.k8s)

aws route53 change-resource-record-sets \
  --hosted-zone-id ZXXXXXXXXXXXXX \
  --change-batch '{
    "Changes": [
      {
        "Action": "CREATE",
        "ResourceRecordSet": {
          "Name": "my-app.example.com",
          "Type": "A",
          "AliasTarget": {
            "HostedZoneId": "Z2FDTNDATAQYW2",
            "DNSName": "'${DISTRIBUTION_DOMAIN}'",
            "EvaluateTargetHealth": false
          }
        }
      }
    ]
  }' \
  --profile conao3.k8s
```

### 3. Update CloudFront Aliases

Add `my-app.example.com` to the CloudFront Aliases list (see step 3 above).

### 4. Deploy

```bash
AWS_PROFILE=conao3.k8s bin/k8s deploy
```

## Access Without Custom Domain

If you don't have a custom domain, you can still access applications via:

**CloudFront URL** (no Host header required):
```bash
curl https://xxx.cloudfront.net/
```

This routes to the default healthcheck Ingress (app1).

**With Host Header**:
```bash
curl -H "Host: app1.example.com" https://xxx.cloudfront.net/
curl -H "Host: app2.example.com" https://xxx.cloudfront.net/
curl -H "Host: app3.example.com" https://xxx.cloudfront.net/
```

## Traefik Dashboard

Access the Traefik dashboard (not exposed publicly by default):

```bash
# Port forward
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl port-forward -n traefik svc/traefik 8080:8080'

# Open browser: http://localhost:8080
```

The dashboard shows:
- Active routers and services
- Middleware configurations
- TLS certificates
- Health checks

## Troubleshooting

### Ingress Not Working

1. Check Ingress status:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get ingress -A'
```

2. Check Traefik logs:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl logs -n traefik -l app=traefik --tail=50'
```

3. Check IngressClass:
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node login 'kubectl get ingressclass'
```

### 404 Not Found

- Verify the service exists and has endpoints
- Check if the Ingress `host` matches the request hostname
- Verify IngressClass is set to `traefik`

### SSL/TLS Issues

- Verify ACM certificate is validated
- Check CloudFront ViewerCertificate configuration
- Verify Route53 records point to CloudFront

## Advanced Configuration

### Custom Headers

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-app
  annotations:
    traefik.ingress.kubernetes.io/router.middlewares: default-add-headers@kubernetescrd
spec:
  # ... rules ...
```

### Rate Limiting

Rate limiting is configured at the CloudFront WAF level (2000 requests per 5 minutes per IP).

For additional rate limiting at the Ingress level, configure Traefik middleware.

### Basic Auth

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-app
  annotations:
    traefik.ingress.kubernetes.io/auth-type: basic
    traefik.ingress.kubernetes.io/auth-secret: basic-auth-secret
spec:
  # ... rules ...
```

Create the secret:

```bash
htpasswd -c auth username
kubectl create secret generic basic-auth-secret --from-file=auth
```
