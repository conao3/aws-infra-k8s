{
  modulesPath,
  pkgs,
  lib,
  ...
}: let
  # Ref: https://github.com/aws/aws-ec2-instance-connect-config
  # Ref: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-connect-set-up.html
  ec2-instance-connect-scripts = pkgs.stdenv.mkDerivation {
    pname = "ec2-instance-connect-scripts";
    version = "1.1.17";

    src = pkgs.fetchFromGitHub {
      owner = "aws";
      repo = "aws-ec2-instance-connect-config";
      rev = "1.1.17";
      sha256 = "sha256-XXrVcmgsYFOj/1cD45ulFry5gY7XOkyhmDV7yXvgNhI=";
    };

    nativeBuildInputs = with pkgs; [makeWrapper];

    patchPhase = ''
      substituteInPlace src/bin/eic_curl_authorized_keys \
        --replace-fail 'OPENSSL=/usr/bin/openssl' 'OPENSSL=${pkgs.openssl}/bin/openssl' \
        --replace-fail 'ca_path=/etc/ssl/certs' 'ca_path=${pkgs.cacert}/etc/ssl/certs' \
        --replace-fail '/usr/bin/curl' '${pkgs.curl}/bin/curl' \
        --replace-fail '/usr/bin/logger' '${pkgs.util-linux}/bin/logger' \
        --replace-fail '/usr/bin/printf' '${pkgs.coreutils}/bin/printf' \
        --replace-fail '/usr/bin/head' '${pkgs.coreutils}/bin/head' \
        --replace-fail '/usr/bin/cut' '${pkgs.coreutils}/bin/cut' \
        --replace-fail '/usr/bin/id' '${pkgs.coreutils}/bin/id' \
        --replace-fail '/usr/bin/base64' '${pkgs.coreutils}/bin/base64' \
        --replace-fail '/bin/echo' '${pkgs.coreutils}/bin/echo' \
        --replace-fail '/bin/grep' '${pkgs.gnugrep}/bin/grep' \
        --replace-fail '/bin/sed' '${pkgs.gnused}/bin/sed' \
        --replace-fail '/bin/cat' '${pkgs.coreutils}/bin/cat' \
        --replace-fail '/bin/mktemp' '${pkgs.coreutils}/bin/mktemp' \
        --replace-fail '/bin/chmod' '${pkgs.coreutils}/bin/chmod'

      substituteInPlace src/bin/eic_parse_authorized_keys \
        --replace-fail '/usr/bin/logger' '${pkgs.util-linux}/bin/logger' \
        --replace-fail '/usr/bin/printf' '${pkgs.coreutils}/bin/printf' \
        --replace-fail '/usr/bin/awk' '${pkgs.gawk}/bin/awk' \
        --replace-fail '/usr/bin/find' '${pkgs.findutils}/bin/find' \
        --replace-fail '/usr/bin/seq' '${pkgs.coreutils}/bin/seq' \
        --replace-fail '/usr/bin/tr' '${pkgs.coreutils}/bin/tr' \
        --replace-fail '/usr/bin/cp "$' '${pkgs.coreutils}/bin/cp "$' \
        --replace-fail '/usr/bin/ssh-keygen' '${pkgs.openssh}/bin/ssh-keygen' \
        --replace-fail '/bin/echo' '${pkgs.coreutils}/bin/echo' \
        --replace-fail '/bin/sed' '${pkgs.gnused}/bin/sed' \
        --replace-fail '/bin/cat' '${pkgs.coreutils}/bin/cat' \
        --replace-fail '/bin/mktemp' '${pkgs.coreutils}/bin/mktemp' \
        --replace-fail '/bin/chmod' '${pkgs.coreutils}/bin/chmod' \
        --replace-fail '/bin/cp "$' '${pkgs.coreutils}/bin/cp "$' \
        --replace-fail '/bin/rm' '${pkgs.coreutils}/bin/rm' \
        --replace-fail '/bin/mv' '${pkgs.coreutils}/bin/mv' \
        --replace-fail '/bin/touch' '${pkgs.coreutils}/bin/touch' \
        --replace-fail '/bin/date' '${pkgs.coreutils}/bin/date'

      substituteInPlace src/bin/eic_run_authorized_keys \
        --replace-fail '/usr/bin/timeout' '${pkgs.coreutils}/bin/timeout'
    '';

    installPhase = ''
      mkdir -p $out/bin

      install -m 755 src/bin/eic_run_authorized_keys $out/bin/
      install -m 755 src/bin/eic_curl_authorized_keys $out/bin/
      install -m 755 src/bin/eic_parse_authorized_keys $out/bin/
    '';
  };
in {
  imports = [
    "${modulesPath}/virtualisation/amazon-image.nix"
    ../nixos-configuration.nix
  ];

  ec2.hvm = true;

  users.users.ec2-instance-connect = {
    isSystemUser = true;
    group = "ec2-instance-connect";
  };
  users.groups.ec2-instance-connect = {};

  services.openssh.settings.AuthorizedKeysFile = lib.mkForce ".ssh/authorized_keys";
  services.openssh.authorizedKeysCommand = lib.mkForce "${ec2-instance-connect-scripts}/bin/eic_run_authorized_keys %u %f";
  services.openssh.authorizedKeysCommandUser = "ec2-instance-connect";

  systemd.services.fetch-ec2-ssh-key = {
    description = "Fetch EC2 SSH key from metadata service";
    wantedBy = ["multi-user.target"];
    after = ["network-online.target"];
    wants = ["network-online.target"];
    serviceConfig = {
      Type = "oneshot";
      RemainAfterExit = true;
    };
    script = ''
      TOKEN=$(${pkgs.curl}/bin/curl -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null || echo "")

      if [ -n "$TOKEN" ]; then
        PUBLIC_KEY=$(${pkgs.curl}/bin/curl -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-keys/0/openssh-key 2>/dev/null)
      else
        PUBLIC_KEY=$(${pkgs.curl}/bin/curl http://169.254.169.254/latest/meta-data/public-keys/0/openssh-key 2>/dev/null)
      fi

      if [ -n "$PUBLIC_KEY" ]; then
        mkdir -p /root/.ssh
        echo "$PUBLIC_KEY" > /root/.ssh/authorized_keys
        chmod 600 /root/.ssh/authorized_keys
        chmod 700 /root/.ssh
      fi
    '';
  };
}
