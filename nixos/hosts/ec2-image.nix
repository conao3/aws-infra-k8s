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
}
