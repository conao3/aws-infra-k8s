# aws-infra-k8s

AWS infrastructure management for Kubernetes using Clojure and CloudFormation.

## Overview

This project provides a modular approach to deploying AWS infrastructure components. Each module handles a specific aspect of the infrastructure and can be deployed independently or all at once.

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
- `eice`
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
    ├── ec2-image.nix        # EC2-specific configuration (x86_64)
    └── vm.nix               # VM-specific configuration (x86_64)
```

### Build and Deploy Custom AMI

You can build and deploy a custom NixOS AMI (x86_64) using the configuration in `nixos/hosts/ec2-image.nix`.

Using the `bin/image` script:
```bash
bin/image build    # Build the custom image
bin/image upload   # Upload to S3, import snapshot, and register as AMI
bin/image deploy   # Deploy cluster with custom AMI
```

Or using make:
```bash
make build-image
make upload-image
make deploy-cluster-custom
```

The `upload` command creates a file `target/ami-id.txt` with the new AMI ID.

Or manually specify AMI ID:
```bash
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy cluster --ami-id ami-xxxxx
```

### Test Custom Image with VM

You can test your custom NixOS configuration locally using QEMU before deploying to AWS.

Using make:
```bash
make run-vm
```

Or directly with nix:
```bash
nix run .#vm
QEMU_OPTS="-m 8192 -smp 8" nix run .#vm
```

Default configuration: 4GB RAM, 4 CPU cores

To exit, press `Ctrl-A` then `X`.

### Search Official NixOS AMI

Ref: https://nixos.org/download/#nixos-amazon

```bash
aws ec2 describe-images --owners 427812963091 --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=x86_64' --query 'sort_by(Images, &CreationDate)[-1].[ImageId,Name]' --output text --profile conao3.k8s
```

Default AMI: `ami-0e08d1626421f5ec4` (nixos/25.05.813814.ac62194c3917-x86_64-linux)

## License

See LICENSE file for details.
