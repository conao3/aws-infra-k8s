{pkgs, ...}: {
  system.stateVersion = "25.05";

  environment.systemPackages = with pkgs; [
    curl
    vim
  ];

  services.k3s = {
    enable = true;
    role = "server";
    extraFlags = toString [
      "--disable=traefik" # use ALB instead
      "--disable=servicelb" # use ALB/NLB instead
      "--write-kubeconfig-mode=644" # allow non-root users to access kubeconfig
    ];
  };

  networking.firewall.allowedTCPPorts = [6443]; # k8s API server
}
