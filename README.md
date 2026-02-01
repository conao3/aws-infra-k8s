# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular approach to deploying AWS infrastructure components. Each module handles a specific aspect of the infrastructure and can be deployed independently or all at once.

All AWS operations use the `AWS_PROFILE` environment variable to specify credentials. Set it before running any commands:

```bash
export AWS_PROFILE=conao3.k8s
# or prefix each command:
AWS_PROFILE=conao3.k8s <command>
```

## Pre-requires

### Add keypair

```bash
aws ec2 create-key-pair --key-name dev-k8s-keypair --query 'KeyMaterial' --output text --profile conao3.k8s > ~/.ssh/dev-k8s-keypair.pem
chmod 400 ~/.ssh/dev-k8s-keypair.pem
```

### Add S3 bucket and VM Import role

Deploy S3 bucket and VM Import role (required for custom AMI upload):
```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy s3
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy vm-import
```

### Add secret for RDS

```
cat <<EOF > /tmp/dev-k8s-secret.json
{"rds-postgres-password":"ChangeMe12345"}
EOF

aws secretsmanager create-secret --name dev-k8s-secret --secret-string file:///tmp/dev-k8s-secret.json --profile conao3.k8s
rm /tmp/dev-k8s-secret.json
```

Update secret.

```
aws secretsmanager get-secret-value --secret-id dev-k8s-secret --query SecretString --output text --profile conao3.k8s | jq . > /tmp/dev-k8s-secret.json
$EDITOR /tmp/dev-k8s-secret.json
aws secretsmanager update-secret --secret-id dev-k8s-secret --secret-string file:///tmp/dev-k8s-secret.json --profile conao3.k8s
rm /tmp/dev-k8s-secret.json
```

## Deployment

### Deploy All Modules

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all
```

With custom AMI:
```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy all --ami-id ami-xxxxx
```

### Deploy Individual Module
Deploy each modules like this.
Currently below modules are provided.

- `s3` (S3 bucket for VM Import)
- `vm-import` (VM Import IAM role for custom AMI upload)
- `network`
- `routing`
- `security-group`
- `cluster`
- `ssh-tunnel`
- `ami-builder` (AMI builder instance, not included in `deploy all`)
- `eice`
- `github-oidc` (GitHub Actions OIDC provider and IAM role)
- `rds`
- `cognito`

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy <module-name>
```

For example, to deploy only the routing module:

```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy routing
```

## Custom NixOS AMI

### NixOS Configuration Structure

```
nixos/
├── nixos-configuration.nix  # Common configuration
└── hosts/
    ├── ec2-image.nix        # EC2-specific configuration (aarch64)
    └── vm.nix               # VM-specific configuration (x86_64)
```

### Build and Deploy Custom AMI

You can build and deploy a custom NixOS AMI (aarch64) using the configuration in `nixos/hosts/ec2-image.nix`.

#### Local Build (requires Nix with aarch64 support)

```bash
AWS_PROFILE=conao3.k8s bin/image build    # Build the custom image
AWS_PROFILE=conao3.k8s bin/image upload   # Upload to S3, import snapshot, and register as AMI
AWS_PROFILE=conao3.k8s bin/image deploy   # Deploy cluster with custom AMI
```

The `upload` command:
- Saves the AMI ID to SSM Parameter Store (`/dev-k8s/custom-ami-id`)
- Creates a local file `target/ami-id.txt` with the AMI ID

#### GitHub Actions Build (Recommended)

Use the **Build NixOS AMI** workflow in the GitHub Actions tab to build the AMI on ARM64 runners. The workflow automatically:
1. Builds the NixOS image natively on aarch64
2. Uploads to S3 and imports as a snapshot
3. Registers the AMI
4. Saves the AMI ID to SSM Parameter Store

The cluster deployment automatically uses the AMI ID from SSM Parameter Store (`/dev-k8s/custom-ami-id`).

### Test Custom Image with VM

You can test your custom NixOS configuration locally using QEMU before deploying to AWS.

```bash
nix run .#vm
```

Or with custom resources:
```bash
QEMU_OPTS="-m 8192 -smp 8" nix run .#vm
```

Default configuration: 4GB RAM, 4 CPU cores

To exit, press `Ctrl-A` then `X`.

### Search Official NixOS AMI

Ref: https://nixos.org/download/#nixos-amazon

```bash
aws ec2 describe-images --owners 427812963091 --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=arm64' --query 'sort_by(Images, &CreationDate)[-1].[ImageId,Name]' --output text --profile conao3.k8s
```

Default AMI: `ami-00ce0dbbbd1a71d5b` (nixos/25.05.813814.ac62194c3917-aarch64-linux)

## SSH Access

Connect to instances via EC2 Instance Connect Endpoint (EICE):

### AMI Builder Instance
```bash
AWS_PROFILE=conao3.k8s bin/ssh/ami-builder
```

### Cluster Node Instance
```bash
AWS_PROFILE=conao3.k8s bin/ssh/node
```

Both scripts use EICE to establish secure SSH connections without requiring public IP addresses.

## License

See LICENSE file for details.
