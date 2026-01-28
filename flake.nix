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

  outputs = inputs @ {
    self,
    nixpkgs,
    flake-parts,
    ...
  }:
    flake-parts.lib.mkFlake {inherit inputs;} {
      systems = ["x86_64-linux" "aarch64-darwin"];
      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      flake = {
        nixosConfigurations.ec2-aarch64-vm = nixpkgs.lib.nixosSystem {
          system = "aarch64-linux";
          modules = [
            {
              networking.hostName = "ec2-aarch64-vm";
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
              virtualisation.diskSize = 20 * 1024;
            }
            "${self}/nixos/hosts/vm.nix"
          ];
        };
      };

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
        packages = {
          imageAmazonAarch64 = inputs.nixos-generators.nixosGenerate {
            system = "aarch64-linux";
            format = "amazon";
            modules = [
              {
                nix.registry.nixpkgs.flake = inputs.nixpkgs;
                virtualisation.diskSize = 20 * 1024;
              }
              "${self}/nixos/hosts/ec2-image.nix"
            ];
          };
          vmAarch64 = self.nixosConfigurations.ec2-aarch64-vm.config.system.build.vm;
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            graalvm
            clojure
            awscli2
            aws-sam-cli
            qemu
          ];
        };

        treefmt.config = {
          programs.alejandra.enable = true;
        };
      };
    };
}
