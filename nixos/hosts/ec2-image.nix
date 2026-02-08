{
  modulesPath,
  pkgs,
  lib,
  ...
}: {
  imports = [
    "${modulesPath}/virtualisation/amazon-image.nix"
    ../nixos-configuration.nix
  ];

  virtualisation.diskSize = 8192;

  fileSystems."/" = {
    device = "/dev/disk/by-label/nixos";
    fsType = "ext4";
    autoResize = true;
  };

  boot.growPartition = true;
  boot.loader.grub.device = lib.mkForce "/dev/xvda";
  boot.loader.timeout = 1;

  ec2.hvm = true;
}
