{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/bfc1b8a4574108ceef22f02bafcf6611380c100d";
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
        nixosConfigurations.ec2-x86_64 = nixpkgs.lib.nixosSystem {
          system = "x86_64-linux";
          modules = [
            {
              nix.registry.nixpkgs.flake = inputs.nixpkgs;
            }
            "${self}/nixos/hosts/ec2-image.nix"
          ];
        };

        nixosConfigurations.ec2-aarch64 = nixpkgs.lib.nixosSystem {
          system = "aarch64-linux";
          modules = [
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
        guestSystem =
          if system == "aarch64-darwin"
          then "aarch64-linux"
          else system;
      in {
        packages = {
          vm =
            (nixpkgs.lib.nixosSystem {
              system = guestSystem;
              modules =
                [
                  {
                    networking.hostName = "nixos-vm";
                    nix.registry.nixpkgs.flake = inputs.nixpkgs;
                    virtualisation.diskSize = 20 * 1024;
                  }
                  "${self}/nixos/hosts/vm.nix"
                ]
                ++ nixpkgs.lib.optionals (guestSystem != system) [
                  {virtualisation.host.pkgs = import inputs.nixpkgs {inherit system;};}
                ];
            }).config.system.build.vm;
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
