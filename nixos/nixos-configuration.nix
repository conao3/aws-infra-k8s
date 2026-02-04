{pkgs, ...}: {
  system.stateVersion = "25.05";

  boot.supportedFilesystems = ["nfs"];

  environment.systemPackages = with pkgs; [
    curl
    vim
  ];

  networking.localCommands = ''
    ip route add 169.254.169.254/32 dev eth0 metric 100
  '';

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
