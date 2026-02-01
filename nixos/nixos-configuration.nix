{pkgs, ...}: {
  system.stateVersion = "25.05";

  environment.systemPackages = with pkgs; [
    curl
    vim
    awscli2
  ];

  # Attach and mount EBS data volume on boot
  systemd.services.attach-data-volume = {
    description = "Attach and mount EBS data volume for k3s persistent storage";
    wantedBy = ["multi-user.target"];
    before = ["k3s.service"];
    requiredBy = ["k3s.service"];
    path = with pkgs; [curl awscli2 util-linux e2fsprogs gawk];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    script = ''
      set -euo pipefail

      # Get instance metadata via IMDSv2
      TOKEN=$(curl -sf -X PUT "http://169.254.169.254/latest/api/token" \
        -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
      INSTANCE_ID=$(curl -sf -H "X-aws-ec2-metadata-token: $TOKEN" \
        http://169.254.169.254/latest/meta-data/instance-id)
      AZ=$(curl -sf -H "X-aws-ec2-metadata-token: $TOKEN" \
        http://169.254.169.254/latest/meta-data/placement/availability-zone)
      REGION=$(echo "$AZ" | sed 's/[a-z]$//')

      # Find data volume by tag
      VOLUME_ID=$(aws ec2 describe-volumes \
        --region "$REGION" \
        --filters "Name=tag:k3s-data-volume,Values=true" "Name=availability-zone,Values=$AZ" \
        --query 'Volumes[0].VolumeId' \
        --output text)

      if [ -z "$VOLUME_ID" ] || [ "$VOLUME_ID" = "None" ]; then
        echo "ERROR: No data volume found with tag k3s-data-volume=true in $AZ" >&2
        exit 1
      fi

      echo "Found data volume: $VOLUME_ID"

      # Wait for volume to become available (old instance may still be detaching)
      for i in $(seq 1 30); do
        VOLUME_STATE=$(aws ec2 describe-volumes \
          --region "$REGION" \
          --volume-ids "$VOLUME_ID" \
          --query 'Volumes[0].State' \
          --output text)

        if [ "$VOLUME_STATE" = "available" ]; then
          break
        elif [ "$VOLUME_STATE" = "in-use" ]; then
          ATTACHED_INSTANCE=$(aws ec2 describe-volumes \
            --region "$REGION" \
            --volume-ids "$VOLUME_ID" \
            --query 'Volumes[0].Attachments[0].InstanceId' \
            --output text)
          if [ "$ATTACHED_INSTANCE" = "$INSTANCE_ID" ]; then
            echo "Volume already attached to this instance"
            break
          fi
          echo "Volume attached to $ATTACHED_INSTANCE, waiting... ($i/30)"
        fi
        sleep 10
      done

      # Attach if available
      VOLUME_STATE=$(aws ec2 describe-volumes \
        --region "$REGION" \
        --volume-ids "$VOLUME_ID" \
        --query 'Volumes[0].State' \
        --output text)

      if [ "$VOLUME_STATE" = "available" ]; then
        echo "Attaching volume $VOLUME_ID to $INSTANCE_ID"
        aws ec2 attach-volume \
          --region "$REGION" \
          --volume-id "$VOLUME_ID" \
          --instance-id "$INSTANCE_ID" \
          --device /dev/xvdf

        aws ec2 wait volume-in-use \
          --region "$REGION" \
          --volume-ids "$VOLUME_ID"

        sleep 5
      fi

      # Find device by volume ID serial number (NVMe on Nitro instances)
      VOLUME_ID_CLEAN=$(echo "$VOLUME_ID" | tr -d '-')
      DEVICE=""
      for i in $(seq 1 30); do
        DEVICE=$(lsblk -o NAME,SERIAL -nr | grep "$VOLUME_ID_CLEAN" | awk '{print "/dev/" $1}' | head -1)
        if [ -n "$DEVICE" ]; then
          break
        fi
        sleep 2
      done

      if [ -z "$DEVICE" ]; then
        echo "ERROR: Could not find device for volume $VOLUME_ID" >&2
        exit 1
      fi

      echo "Found device: $DEVICE"

      # Format if no filesystem exists (first-time setup)
      if ! blkid "$DEVICE" &>/dev/null; then
        echo "Formatting new volume $DEVICE with ext4"
        mkfs.ext4 "$DEVICE"
      fi

      # Mount
      mkdir -p /mnt/data
      if ! mountpoint -q /mnt/data; then
        mount "$DEVICE" /mnt/data
        echo "Mounted $DEVICE at /mnt/data"
      fi

      # Ensure k3s storage directory exists
      mkdir -p /mnt/data/k3s-storage
    '';
  };

  services.k3s = {
    enable = true;
    role = "server";
    extraFlags = toString [
      "--disable=traefik" # use ALB instead
      "--disable=servicelb" # use ALB/NLB instead
      "--write-kubeconfig-mode=644" # allow non-root users to access kubeconfig
      "--default-local-storage-path=/mnt/data/k3s-storage" # use EBS volume for persistent data
    ];
  };

  networking.firewall.allowedTCPPorts = [6443]; # k8s API server
}
