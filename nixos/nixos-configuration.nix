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
    path = [ pkgs.awscli2 pkgs.bash pkgs.coreutils ];
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
        TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
        PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" "http://169.254.169.254/latest/meta-data/local-ipv4")
        mkdir -p /etc/rancher/k3s
        cat > /etc/rancher/k3s/config.yaml <<EOF
        tls-san:
          - $PRIVATE_IP
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
