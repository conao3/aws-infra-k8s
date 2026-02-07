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
      ExecStart = "${pkgs.bash}/bin/bash -c '${./scripts/refresh-ecr-token.sh}'";
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
