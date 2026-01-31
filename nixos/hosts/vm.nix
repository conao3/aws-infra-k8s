{modulesPath, ...}: {
  imports = [
    "${modulesPath}/virtualisation/qemu-vm.nix"
    ../nixos-configuration.nix
  ];

  users.users.root.initialPassword = "root";

  services.xserver.enable = false;
  virtualisation.graphics = false;
}
