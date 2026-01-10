# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular approach to deploying AWS infrastructure components. Each module handles a specific aspect of the infrastructure and can be deployed independently or all at once.

## Pre-requires

Add keypair.

```
aws ec2 create-key-pair --key-name dev-k8s-keypair --query 'KeyMaterial' --output text --profile conao3.k8s > ~/.ssh/dev-k8s-keypair.pem
chmod 400 ~/.ssh/dev-k8s-keypair.pem
```

## Deploy

Deploy all via this command.

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
Deploy each modules like this.
Currently below modules are provided.

- `network`
- `routing`
- `security-group`
- `cluster`
- `ssh-tunnel`
- `eice`

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

For example, to deploy only the routing module:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```

## Search NixOS ami

Ref: https://nixos.org/download/#nixos-amazon

```
aws ec2 describe-images --owners 427812963091 --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=arm64' --query 'sort_by(Images, &CreationDate)' --profile conao3.k8s | jq -r '.[] | [.Name, .SourceImageId] | @csv'
```

Sample output.
```
"nixos/25.05.808519.9cb344e96d5b-aarch64-linux","ami-0f913a43e93891b14"
"nixos/25.05.809091.41d292bfc373-aarch64-linux","ami-04357df6ca2259145"
"nixos/25.05.809451.fe83bbdde2cc-aarch64-linux","ami-0293a9f7c3fa550e8"
"nixos/25.05.809711.8cd5ce828d5d-aarch64-linux","ami-0d16d357cc1c719fa"
"nixos/25.05.809980.e9b7f2ff62b3-aarch64-linux","ami-04f552791138b87f8"
"nixos/25.05.810061.d2ed99647a4b-aarch64-linux","ami-0683aebbb974074c6"
"nixos/25.05.810395.25e53aa156d4-aarch64-linux","ami-0cfe8294b436895d3"
"nixos/25.05.810995.5da4a26309e7-aarch64-linux","ami-06b684a5610a10495"
"nixos/25.05.811339.98ff3f9af268-aarch64-linux","ami-03ed3f8468e5819f0"
"nixos/25.05.811621.c8aa8cc00a5c-aarch64-linux","ami-09d01ec47db9c7394"
"nixos/25.05.811874.daf6dc47aa4b-aarch64-linux","ami-06371070e4cda92c0"
"nixos/25.05.812778.3acb677ea67d-aarch64-linux","ami-09aa74e80fadac3b7"
```

You should use last one, `ami-09aa74e80fadac3b7`.

## License

See LICENSE file for details.
