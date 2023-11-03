#!/bin/bash

echo "Cleaning up the aws zookeeper instance!"
kubectl delete po -n metallb-system --all
kubectl delete service zk-hs
kubectl delete service zk-cs
kubectl delete statefulset zk
kubectl delete pvc zoo-pvc
kubectl delete pv zoo-pv
echo "done!"
