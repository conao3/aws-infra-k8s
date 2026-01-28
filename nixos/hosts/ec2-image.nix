{
  modulesPath,
  pkgs,
  ...
}: {
  imports = [
    "${modulesPath}/virtualisation/amazon-image.nix"
    ../nixos-configuration.nix
  ];

  ec2.hvm = true;

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
