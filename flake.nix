{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    treefmt-nix.url = "github:numtide/treefmt-nix";
    nixos-generators = {
      url = "github:nix-community/nixos-generators";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = inputs @ {flake-parts, ...}:
    flake-parts.lib.mkFlake {inherit inputs;} {
      systems = ["x86_64-linux" "aarch64-darwin"];
      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      perSystem = {
        pkgs,
        system,
        ...
      }: let
        overlay = final: prev: let
          graalvm = prev.graalvmPackages.graalvm-ce;
          clojure = prev.clojure.override {jdk = graalvm;};
        in {
          inherit graalvm clojure;
        };
        pkgs = import inputs.nixpkgs {
          inherit system;
          overlays = [overlay];
        };
      in {
        packages.imageAmazonAarch64 = inputs.nixos-generators.nixosGenerate {
          system = "aarch64-linux";
          format = "amazon";
          modules = [
            {
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
              virtualisation.diskSize = 20 * 1024;
            }
            ./nixos/ec2-image.nix
          ];
        };

        packages.imageAmazonX86_64 = inputs.nixos-generators.nixosGenerate {
          system = "x86_64-linux";
          format = "amazon";
          modules = [
            ./nixos/ec2-image.nix
          ];
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            graalvm
            clojure
            awscli2
            aws-sam-cli
          ];
        };

        treefmt.config = {
          programs.alejandra.enable = true;
        };
      };
    };
}
