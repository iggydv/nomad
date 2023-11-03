#!/bin/bash
#
# Depends on https://gist.github.com/i11/433fcbcbfcedb677a26673426d304fc1#file-trusty-kubernetes-sh

# Assuming logged in as normal user
sudo -i

# Update
apt-get update
apt-get upgrade -y
apt-get dist-upgrade -y

# Ideally reboot should happen here...

# Install systemd
apt-get install -y systemd-services systemd

# Docker
apt-get install -y \
linux-image-extra-$(uname -r) \
linux-image-extra-virtual

apt-get install -y \
apt-transport-https \
ca-certificates \
curl \
software-properties-common

apt-key adv --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys 7EA0A9C3F273FCD8

add-apt-repository \
"deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

apt-get update

apt-get install -y docker-ce

# Convert docker upstart to systemd
stop docker
mv /lib/systemd/system/docker.* /lib/systemd/upstart
rm -f /etc/init/docker.conf
systemctl daemon-reload
systemctl start docker

# Kubernetes
curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key add -

cat <<EOF >/etc/apt/sources.list.d/kubernetes.list
# Yes, xenial on trusty
deb http://apt.kubernetes.io/ kubernetes-xenial main
EOF

apt-get update

apt-get install -y kubernetes-cni kubectl=1.8.3-00

apt-get install -y binutils ebtables socat

curl -O https://gist.githubusercontent.com/i11/433fcbcbfcedb677a26673426d304fc1/raw/710845996a762aeceaf3cd47c8bbd79555d224c5/trusty-kubernetes.sh
chmod +x trusty-kubernetes.sh
./trusty-kubernetes.sh

apt-get install -y kubeadm

kubeadm init

logout

mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config

# Bring up the overlay network
# Weave
export kubever=$(kubectl version | base64 | tr -d '\n')
kubectl apply -f "https://cloud.weave.works/k8s/net?k8s-version=$kubever"
#kubectl apply --filename https://git.io/weave-kube-1.6
# Flannel
# kubectl create -f https://raw.githubusercontent.com/coreos/flannel/v0.9.0/Documentation/kube-flannel.yml

# Dashboard
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/master/src/deploy/recommended/kubernetes-dashboard.yaml

# Give admin rights to kubernetes-dashboard SA - WARNING! Insecure!
cat <<'EOF' >dashboard-admin.yaml
apiVersion: rbac.authorization.k8s.io/v1beta1
kind: ClusterRoleBinding
metadata:
  name: kubernetes-dashboard
  labels:
    k8s-app: kubernetes-dashboard
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
- kind: ServiceAccount
  name: kubernetes-dashboard
  namespace: kube-system
EOF

kubectl create -f dashboard-admin.yaml
