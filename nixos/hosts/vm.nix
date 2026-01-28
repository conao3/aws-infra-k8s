{modulesPath, ...}: {
  imports = [
    "${modulesPath}/virtualisation/qemu-vm.nix"
    ../nixos-configuration.nix
  ];

  users.users.root.initialPassword = "root";

  virtualisation.qemu.options = ["-nographic"];
  virtualisation.qemu.consoles = ["ttyAMA0"];
  boot.kernelParams = [
    "console=ttyAMA0"
    "systemd.unified_cgroup_hierarchy=0"
    "SYSTEMD_CGROUP_ENABLE_LEGACY_FORCE=1"
  ];
  boot.loader.timeout = 0;

  services.xserver.enable = false;

  systemd.services.systemd-udevd.serviceConfig.SystemCallFilter = "";
}
