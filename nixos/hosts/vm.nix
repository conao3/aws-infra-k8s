{modulesPath, ...}: {
  imports = [
    "${modulesPath}/virtualisation/qemu-vm.nix"
    ../nixos-configuration.nix
  ];

  users.users.root.initialPassword = "root";

  virtualisation.graphics = false;
}
