# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular approach to deploying AWS infrastructure components. Each module handles a specific aspect of the infrastructure and can be deployed independently or all at once.

## Prerequisites

- AWS CLI configured with appropriate credentials
- Clojure CLI tools installed

## Available Modules

| Module | Description |
|--------|-------------|
| `network` | VPC, subnets, and core networking resources |
| `routing` | Route tables and internet gateway configuration |
| `security-group` | Security group rules and policies |
| `ssh-tunnel` | SSH tunneling configuration |
| `eice` | EC2 Instance Connect Endpoint setup |

## Deployment

### Deploy All Modules

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

### Deploy Individual Module

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

For example, to deploy only the routing module:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```

## License

See LICENSE file for details.
