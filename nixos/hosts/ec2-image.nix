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

  virtualisation = {
    memorySize = 4096;
    diskSize = 8192;
  };
}
