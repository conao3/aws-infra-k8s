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

Add secret for RDS.

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
    ├── ec2-image.nix        # EC2-specific configuration
    └── vm.nix               # VM-specific configuration
```

### Build and Deploy Custom AMI

You can build and deploy a custom NixOS AMI using the configuration in `nixos/hosts/ec2-image.nix`.

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

Using the `bin/vm` script:
```bash
bin/vm run
```

Or using make:
```bash
make run-vm
```

Or directly with nix:
```bash
QEMU_OPTS="-m 16384 -smp 8" nix run .#vmAarch64
```

Default configuration: 10GB RAM, 6 CPU cores

**Note:** This will run slowly on x86_64 hosts due to aarch64 emulation.

To exit, press `Ctrl-A` then `X`.

### Search Official NixOS AMI

Ref: https://nixos.org/download/#nixos-amazon

```bash
aws ec2 describe-images --owners 427812963091 --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=arm64' --query 'sort_by(Images, &CreationDate)' --profile conao3.k8s | jq -r '.[] | [.Name, .SourceImageId] | @csv'
```

Sample output:
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

Default AMI: `ami-09aa74e80fadac3b7`

## License

See LICENSE file for details.
