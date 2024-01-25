#!/bin/bash

set -euxo pipefail -o posix

# misc
sudo yum install -y nmap-ncat
# sudo yum install -y firewalld
# sudo systemctl enable --now firewalld

# docker
sudo yum install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user

# swap off
swapoff -a

# modprove
sudo bash -c 'cat <<EOF > /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF'
sudo modprobe overlay
sudo modprobe br_netfilter

# disable SELinux
sudo setenforce 0
sudo sed -i 's/^SELINUX=enforcing$/SELINUX=permissive/' /etc/selinux/config

# sysctl
sudo bash -c 'cat <<EOF > /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-ip6tables = 1
net.bridge.bridge-nf-call-iptables = 1
net.ipv4.ip_forward = 1
EOF'
sudo sysctl --system

# install k8s
sudo bash -c 'cat <<EOF > /etc/yum.repos.d/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://pkgs.k8s.io/core:/stable:/v1.29/rpm/
enabled=1
gpgcheck=1
gpgkey=https://pkgs.k8s.io/core:/stable:/v1.29/rpm/repodata/repomd.xml.key
exclude=kubelet kubeadm kubectl cri-tools kubernetes-cni
EOF'

sudo yum install -y kubelet kubeadm kubectl --disableexcludes=kubernetes
sudo systemctl enable --now kubelet

# sudo firewall-cmd --add-port=6443/tcp --zone=public --permanent
# sudo firewall-cmd --reload

# # master node
# if [ "${OP_NODE_TYPE}" = "master" ]; then
#     # kubeadm
#     sudo kubeadm init --pod-network-cidr=10.244.0.0/16
#     mkdir -p "${HOME}/.kube"
#     sudo cp -i /etc/kubernetes/admin.conf "${HOME}/.kube/config"
#     sudo chown "$(id -u):$(id -g)" "${HOME}/.kube/config"

#     # flannel
#     curl -LO https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
#     kubectl apply -f kube-flannel.yml
# fi
