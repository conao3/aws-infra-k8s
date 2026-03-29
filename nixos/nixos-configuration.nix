{pkgs, ...}: {
  system.stateVersion = "25.05";

  boot.supportedFilesystems = ["nfs"];

  environment.systemPackages = with pkgs; [
    curl
    vim
    awscli2
  ];

  systemd.services.fix-metadata-route = {
    description = "Fix route to EC2 metadata service";
    after = ["network.target" "k3s.service"];
    wantedBy = ["multi-user.target"];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      ExecStart = "${pkgs.bash}/bin/bash -c '${pkgs.iproute2}/bin/ip route add 169.254.169.254/32 dev ens5 metric 50 || true'";
    };
  };

  # Refresh ECR credentials for k3s private registry access
  systemd.services.k3s-ecr-login = {
    description = "Refresh ECR credentials for k3s";
    after = ["network-online.target" "fix-metadata-route.service"];
    wants = ["network-online.target"];
    requires = ["fix-metadata-route.service"];
    path = [ pkgs.awscli2 pkgs.bash pkgs.coreutils pkgs.curl pkgs.gnugrep ];
    serviceConfig = {
      Type = "oneshot";
      ExecStart = "${pkgs.bash}/bin/bash ${./scripts/refresh-ecr-token.sh}";
    };
  };

  systemd.timers.k3s-ecr-login = {
    description = "Periodically refresh ECR credentials for k3s";
    wantedBy = ["timers.target"];
    timerConfig = {
      OnBootSec = "1min";
      OnUnitActiveSec = "10h";
      Unit = "k3s-ecr-login.service";
    };
  };

  systemd.services.k3s-publish-kubeconfig = {
    description = "Publish k3s kubeconfig to SSM Parameter Store";
    after = ["network-online.target" "k3s.service"];
    wants = ["network-online.target" "k3s.service"];
    requires = ["k3s.service"];
    path = [pkgs.awscli2 pkgs.bash pkgs.coreutils pkgs.gnused];
    serviceConfig = {
      Type = "oneshot";
      ExecStart = pkgs.writeShellScript "k3s-publish-kubeconfig" ''
        set -euo pipefail
        private_ip="$(${pkgs.iproute2}/bin/ip -4 addr show dev ens5 | ${pkgs.gnugrep}/bin/grep -oP 'inet \K[0-9.]+' | head -n1)"
        kubeconfig="$(${pkgs.gnused}/bin/sed "s|127.0.0.1|$private_ip|g" /etc/rancher/k3s/k3s.yaml)"
        ${pkgs.awscli2}/bin/aws ssm put-parameter \
          --name "dev-k8s-kubeconfig" \
          --region "ap-northeast-1" \
          --type "SecureString" \
          --value "$kubeconfig" \
          --overwrite
      '';
    };
  };

  systemd.timers.k3s-publish-kubeconfig = {
    description = "Periodically publish k3s kubeconfig to SSM Parameter Store";
    wantedBy = ["timers.target"];
    timerConfig = {
      OnBootSec = "2min";
      OnUnitActiveSec = "10min";
      Unit = "k3s-publish-kubeconfig.service";
    };
  };

  systemd.services.k3s-config = {
    description = "Generate k3s config with tls-san from instance metadata";
    before = ["k3s.service"];
    requiredBy = ["k3s.service"];
    after = ["network-online.target" "fix-metadata-route.service"];
    wants = ["network-online.target"];
    path = [pkgs.curl pkgs.coreutils];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      ExecStart = pkgs.writeShellScript "k3s-config" ''
        set -euo pipefail
        TOKEN="$(${pkgs.curl}/bin/curl -sS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 21600')"
        INSTANCE_ID="$(${pkgs.curl}/bin/curl -sS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)"
        AZ="$(${pkgs.curl}/bin/curl -sS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/placement/availability-zone)"
        PRIVATE_IP=$(${pkgs.iproute2}/bin/ip -4 addr show dev ens5 | ${pkgs.gnugrep}/bin/grep -oP 'inet \K[0-9.]+')
        mkdir -p /etc/rancher/k3s
        cat > /etc/rancher/k3s/config.yaml <<EOF
        tls-san:
          - $PRIVATE_IP
        kubelet-arg:
          - provider-id=aws:///$AZ/$INSTANCE_ID
        EOF
      '';
    };
  };

  services.k3s = {
    enable = true;
    role = "server";
    extraFlags = toString [
      "--disable=traefik"
      "--disable=servicelb"
      "--write-kubeconfig-mode=644"
      "--kubelet-arg=cloud-provider=external"
    ];
  };

  networking.firewall.allowedTCPPorts = [6443]; # k8s API server
}
