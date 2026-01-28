{modulesPath, ...}: {
  imports = [
    "${modulesPath}/virtualisation/qemu-vm.nix"
    ../nixos-configuration.nix
  ];

  users.users.root.initialPassword = "root";

  virtualisation.qemu.options = ["-nographic"];
  virtualisation.qemu.consoles = ["ttyAMA0"];
  boot.kernelParams = ["console=ttyAMA0"];
  boot.loader.timeout = 0;

  services.xserver.enable = false;
}
