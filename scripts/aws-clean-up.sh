#!/bin/bash

echo "Cleaning up the aws zookeeper instance!"
kubectl delete service zk-hs
kubectl delete statefulset zk
kubectl delete persistentvolumeclaims efs-claim
kubectl delete persistentvolume efs-pv
echo "done!"
