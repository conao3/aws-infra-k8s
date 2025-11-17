{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    treefmt-nix.url = "github:numtide/treefmt-nix";
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
          graalvm = prev.graalvm-ce;
          clojure = prev.clojure.override {jdk = graalvm;};
        in {
          inherit graalvm clojure;
        };
        pkgs = import inputs.nixpkgs {
          inherit system;
          overlays = [overlay];
        };
      in {
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
