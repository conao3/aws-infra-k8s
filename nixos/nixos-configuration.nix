{pkgs, ...}: {
  system.stateVersion = "25.05";

  boot.supportedFilesystems = ["nfs"];

  environment.systemPackages = with pkgs; [
    curl
    vim
  ];

  systemd.services.fix-metadata-route = {
    description = "Fix route to EC2 metadata service";
    after = ["network.target" "k3s.service"];
    wantedBy = ["multi-user.target"];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
      ExecStart = "${pkgs.iproute2}/bin/ip route add 169.254.169.254/32 dev ens5 metric 50 || true";
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
