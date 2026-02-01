{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    treefmt-nix.url = "github:numtide/treefmt-nix";
  };

  outputs = inputs @ {
    self,
    nixpkgs,
    flake-parts,
    ...
  }:
    flake-parts.lib.mkFlake {inherit inputs;} {
      systems = ["x86_64-linux" "aarch64-linux" "aarch64-darwin"];
      imports = [
        inputs.treefmt-nix.flakeModule
      ];

      flake = {
        nixosConfigurations.vm = nixpkgs.lib.nixosSystem {
          system = "x86_64-linux";
          modules = [
            {
              networking.hostName = "nixos-vm";
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
              virtualisation.diskSize = 20 * 1024;
            }
            "${self}/nixos/hosts/vm.nix"
          ];
        };

        nixosConfigurations.ec2-x86_64 = nixpkgs.lib.nixosSystem {
          system = "x86_64-linux";
          modules = [
            "${inputs.nixpkgs}/nixos/modules/virtualisation/amazon-image.nix"
            {
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
            }
            "${self}/nixos/hosts/ec2-image.nix"
          ];
        };

        nixosConfigurations.ec2-aarch64 = nixpkgs.lib.nixosSystem {
          system = "aarch64-linux";
          modules = [
            "${inputs.nixpkgs}/nixos/modules/virtualisation/amazon-image.nix"
            {
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
            }
            "${self}/nixos/hosts/ec2-image.nix"
          ];
        };

        packages.x86_64-linux.imageAmazon = self.nixosConfigurations.ec2-x86_64.config.system.build.images.amazon;
        packages.aarch64-linux.imageAmazon = self.nixosConfigurations.ec2-aarch64.config.system.build.images.amazon;
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
          vm = self.nixosConfigurations.vm.config.system.build.vm;
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            graalvm
            clojure
            awscli2
            aws-sam-cli
            qemu

            # k8s tools
            kubectl
            kind
            k9s
          ];
        };

        treefmt.config = {
          programs.alejandra.enable = true;
        };
      };
    };
}
