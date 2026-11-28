# Custom NixOS AMI

This project uses a custom NixOS AMI for EC2 instances, providing a declarative and reproducible system configuration.

## NixOS Configuration Structure

```
nixos/
├── nixos-configuration.nix  # Common configuration
└── hosts/
    ├── ec2-image.nix        # EC2-specific configuration (aarch64)
    └── vm.nix               # VM-specific configuration (x86_64)
```

### Configuration Files

**nixos-configuration.nix**: Common configuration shared by all hosts
- k3s service configuration
- NFS support for EFS
- EC2 metadata service route fix
- Firewall rules

**hosts/ec2-image.nix**: EC2-specific configuration
- aarch64 architecture
- EC2 AMI module
- Disk size and format

**hosts/vm.nix**: VM-specific configuration for local testing
- x86_64 architecture
- QEMU VM module
- Memory and CPU allocation

## k3s Configuration

The custom AMI includes k3s with the following settings:

```nix
services.k3s = {
  enable = true;
  role = "server";
  extraFlags = toString [
    "--disable=traefik"              # Use custom Traefik deployment
    "--disable=servicelb"            # Use NodePort instead
    "--write-kubeconfig-mode=644"   # Allow non-root access
    "--kubelet-arg=cloud-provider=external"  # For AWS integration
  ];
};
```

### Why Disable Built-in Components?

**Traefik**:
- Custom Traefik deployment provides more control
- Version pinning (v2.11)
- Custom configuration via k8s manifests

**ServiceLB**:
- NodePort + ALB is simpler and more reliable in AWS
- ServiceLB (Klipper) is designed for bare metal, not cloud

See [Architecture FAQ](001-architecture.md#why-disable-traefik-and-servicelb) for details.

## Build and Deploy Custom AMI

The AMI is built in AWS by the `ami-builder` stack (`src/conao3/aws_infra_k8s/ami_builder.clj`), which is not included in `deploy all`. Its Step Functions state machine `<prefix>-ami-builder` runs:

1. **CodeBuild** (`<prefix>-ami-builder`, ARM64): clones the `master` branch of this repository from GitHub, runs `nix build .#imageAmazon` with `buildspec-ami.yml`, uploads the `.vhd` image to the `s3` stack's bucket (export `<prefix>-VmImportBucket`) and writes its S3 URI to SSM `/<prefix>/ami-builder/s3-uri`
2. **Lambda** (`<prefix>-ami-importer`, `lambda/ami-importer`): imports the image as an EBS snapshot (`ec2:ImportSnapshot`), registers it as an arm64 AMI named `nixos-custom-<timestamp>` and saves the AMI ID to SSM `/<prefix>/custom-ami-id`

The `cluster` stack reads the AMI ID from SSM `/<prefix>/custom-ami-id`.

### Prerequisites

Deploy the S3 bucket, the `vmimport` service role that VM Import uses to read the image, and the builder:

```bash
AWS_PROFILE=conao3.dev.k8s clojure -M -m conao3.aws-infra-k8s deploy s3
AWS_PROFILE=conao3.dev.k8s clojure -M -m conao3.aws-infra-k8s deploy vm-import
AWS_PROFILE=conao3.dev.k8s clojure -M -m conao3.aws-infra-k8s deploy ami-builder
```

### Build the AMI

Push the NixOS changes to `master` first, since CodeBuild builds what is on GitHub. Then start the state machine and wait for it:

```bash
STATE_MACHINE_ARN=$(aws cloudformation list-exports \
  --query "Exports[?Name=='dev-k8s-StateMachine'].Value" --output text \
  --profile conao3.dev.k8s)

EXECUTION_ARN=$(aws stepfunctions start-execution \
  --state-machine-arn "${STATE_MACHINE_ARN}" \
  --query executionArn --output text \
  --profile conao3.dev.k8s)

aws stepfunctions describe-execution \
  --execution-arn "${EXECUTION_ARN}" \
  --query status --output text \
  --profile conao3.dev.k8s
```

Repeat `describe-execution` until the status is `SUCCEEDED`, then check the new AMI ID:

```bash
aws ssm get-parameter --name /dev-k8s/custom-ami-id \
  --query Parameter.Value --output text \
  --profile conao3.dev.k8s
```

### Roll Out the AMI

`bin/image deploy` starts an instance refresh of the `<prefix>-node` Auto Scaling group and waits for it to succeed:

```bash
AWS_PROFILE=conao3.dev.k8s bin/image deploy
```

### Build the Image Locally

To check that the image builds before pushing, build it on an aarch64 machine (or with binfmt_misc + QEMU):

```bash
nix build .#packages.aarch64-linux.imageAmazon
```

## Test Custom Image with VM

Test your custom NixOS configuration locally using QEMU before deploying to AWS:

```bash
nix run .#vm
```

**With custom resources**:

```bash
QEMU_OPTS="-m 8192 -smp 8" nix run .#vm
```

**Default configuration**: 4GB RAM, 4 CPU cores

**Exit VM**: Press `Ctrl-A` then `X`

## Use Official NixOS AMI

If you don't want to build a custom AMI, you can use the official NixOS AMI.

### Search Official AMI

```bash
aws ec2 describe-images \
  --owners 427812963091 \
  --filter 'Name=name,Values=nixos/25.05*' 'Name=architecture,Values=arm64' \
  --query 'sort_by(Images, &CreationDate)[-1].[ImageId,Name]' \
  --output text \
  --profile conao3.dev.k8s
```

**Default AMI**: `ami-00ce0dbbbd1a71d5b` (nixos/25.05.813814.ac62194c3917-aarch64-linux)

**Reference**: [NixOS Amazon Downloads](https://nixos.org/download/#nixos-amazon)

### Update Cluster to Use Official AMI

Edit `src/conao3/aws_infra_k8s/cluster.clj` and set the AMI ID:

```clojure
:ImageId "ami-00ce0dbbbd1a71d5b"  ; Official NixOS AMI
```

Then redeploy:

```bash
AWS_PROFILE=conao3.dev.k8s clojure -M -m conao3.aws-infra-k8s deploy cluster
```

## Customizing the AMI

### Modify NixOS Configuration

Edit `nixos/nixos-configuration.nix` to add packages or change settings:

```nix
{pkgs, ...}: {
  environment.systemPackages = with pkgs; [
    curl
    vim
    htop        # Add htop
    jq          # Add jq
  ];

  # Add custom service
  systemd.services.my-service = {
    description = "My Custom Service";
    wantedBy = ["multi-user.target"];
    serviceConfig = {
      ExecStart = "${pkgs.bash}/bin/bash -c 'echo Hello'";
    };
  };
}
```

### Rebuild and Deploy

Commit and push the change to `master`, then follow [Build the AMI](#build-the-ami) and [Roll Out the AMI](#roll-out-the-ami).

## Troubleshooting

### Build Fails

Check the failed step of the Step Functions execution and the CodeBuild logs in CloudWatch Logs (`/aws/codebuild/dev-k8s-ami-builder`), or the local build output.

Common issues:
- Insufficient disk space
- Missing Nix dependencies
- Network issues during package download

### AMI Import Fails

Check the `dev-k8s-ami-importer` Lambda logs and the VM Import task status:

```bash
aws ec2 describe-import-snapshot-tasks --profile conao3.dev.k8s
```

### Instance Won't Start

Check the EC2 instance system log:

```bash
aws ec2 get-console-output \
  --instance-id <instance-id> \
  --profile conao3.dev.k8s
```

## AMI Maintenance

### Update NixOS Version

Edit `nixos/hosts/ec2-image.nix` and update the NixOS version:

```nix
{
  system.stateVersion = "25.11";  # Update version
  # ...
}
```

### Cleanup Old AMIs

List AMIs:

```bash
aws ec2 describe-images \
  --owners self \
  --filters "Name=name,Values=dev-k8s-*" \
  --query 'Images[*].[ImageId,Name,CreationDate]' \
  --output table \
  --profile conao3.dev.k8s
```

Deregister old AMI:

```bash
aws ec2 deregister-image \
  --image-id <ami-id> \
  --profile conao3.dev.k8s
```

Delete associated snapshot:

```bash
aws ec2 delete-snapshot \
  --snapshot-id <snapshot-id> \
  --profile conao3.dev.k8s
```
