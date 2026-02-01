{pkgs, ...}: {
  system.stateVersion = "25.05";

  environment.systemPackages = with pkgs; [
    curl
    vim
  ];
}
