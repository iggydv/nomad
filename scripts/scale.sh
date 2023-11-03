#!/bin/bash

echo "Scaling instance!"
kubectl scale statefulset.apps/zk --replicas=0
kubectl scale statefulset.apps/zk --replicas=1
echo "done!"
