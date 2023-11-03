# Setting up Kubernetes (unix)

[Kubeadm documentation](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/create-cluster-kubeadm/)

```
kubeadm init

# Setting the configuration for regular use
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

## Create a network interface (Flannel)

```
kubectl apply -f https://raw.githubusercontent.com/coreos/flannel/master/Documentation/kube-flannel.yml
```

## Create the dashboard
[kube-dashboard documentation](https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/)
```
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.0.0/aio/deploy/recommended.yaml

# Set up the proxy 
kubectl proxy

# Create a token to be entered into login screen
kubectl -n kube-system describe secret $(kubectl -n kube-system get secret | grep admin-user | awk '{print $1}')
```
The dashboard will be available at  http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard

## Removing taints the master node
This allows for schedules (pod creation) on the master node. This is required in a single node cluster setup.
```
kubectl taint node mymasternode node-role.kubernetes.io/master:NoSchedule-
```

## Getting ready for Zookeeper deployment

### Set up storage provider
[Rook documentation](https://rook.io/docs/rook/v1.5/ceph-quickstart.html)
```
git clone --single-branch --branch v1.5.1 https://github.com/rook/rook.git
cd rook/cluster/examples/kubernetes/ceph
kubectl create -f common.yaml

cd ~/Development/agentsmith/kubernetes
kubectl apply -f storageClass.yaml
```

### Run Zookeeper!

```
kubectl apply -f zookeeper.yaml
```