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

### Option 1: GitHub Actions (Recommended)

Use the **Build NixOS AMI** workflow in the GitHub Actions tab to build the AMI on ARM64 runners.

The workflow automatically:
1. Builds the NixOS image natively on aarch64
2. Uploads to S3 and imports as a snapshot
3. Registers the AMI
4. Saves the AMI ID to SSM Parameter Store (`/dev-k8s/custom-ami-id`)

**Steps**:

```bash
# Trigger the workflow
gh workflow run build-ami.yml

# Wait for completion
sleep 5
gh run watch $(gh run list --workflow=build-ami.yml --limit=1 --json databaseId --jq '.[0].databaseId')

# Deploy cluster with the new AMI
AWS_PROFILE=conao3.k8s ./bin/image deploy
```

### Option 2: Local Build

**Requirements**:
- Nix with aarch64 support (or binfmt_misc + QEMU)
- AWS CLI configured

**Steps**:

```bash
# Build the custom image
AWS_PROFILE=conao3.k8s bin/image build

# Upload to S3, import snapshot, and register as AMI
AWS_PROFILE=conao3.k8s bin/image upload

# Deploy cluster with custom AMI
AWS_PROFILE=conao3.k8s bin/image deploy
```

The `upload` command:
- Saves the AMI ID to SSM Parameter Store (`/dev-k8s/custom-ami-id`)
- Creates a local file `target/ami-id.txt` with the AMI ID

### What Happens During Upload?

1. **Upload to S3**: The raw disk image (`.vhd` format) is uploaded to your S3 bucket
2. **Import Snapshot**: AWS imports the image as an EBS snapshot
3. **Register AMI**: The snapshot is registered as an AMI
4. **Save to SSM**: The AMI ID is saved to Parameter Store for deployment automation

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
  --profile conao3.k8s
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
AWS_PROFILE=conao3.k8s clojure -M -m conao3.aws-infra-k8s deploy cluster
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

```bash
# Build new AMI
gh workflow run build-ami.yml

# Wait for completion
gh run watch $(gh run list --workflow=build-ami.yml --limit=1 --json databaseId --jq '.[0].databaseId')

# Deploy updated cluster
AWS_PROFILE=conao3.k8s ./bin/image deploy
```

## Troubleshooting

### Build Fails

Check the GitHub Actions logs or local build output for errors.

Common issues:
- Insufficient disk space
- Missing Nix dependencies
- Network issues during package download

### AMI Import Fails

Check the VM Import task status:

```bash
aws ec2 describe-import-snapshot-tasks --profile conao3.k8s
```

### Instance Won't Start

Check the EC2 instance system log:

```bash
aws ec2 get-console-output \
  --instance-id <instance-id> \
  --profile conao3.k8s
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
  --profile conao3.k8s
```

Deregister old AMI:

```bash
aws ec2 deregister-image \
  --image-id <ami-id> \
  --profile conao3.k8s
```

Delete associated snapshot:

```bash
aws ec2 delete-snapshot \
  --snapshot-id <snapshot-id> \
  --profile conao3.k8s
```
