{pkgs, ...}: {
  services.openssh = {
    enable = true;
    settings = {
      PermitRootLogin = "prohibit-password";
      PasswordAuthentication = false;
    };
  };

  services.amazon-ssm-agent.enable = true;

  environment.systemPackages = with pkgs; [
    curl
    vim
  ];

  system.stateVersion = "25.05";
}
