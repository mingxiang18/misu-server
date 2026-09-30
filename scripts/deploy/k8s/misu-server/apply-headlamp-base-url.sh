#!/usr/bin/env bash
set -euo pipefail

# Apply the checked-in Headlamp Service and Deployment patches.
# The Service patch is JSON6902 so changing type to ClusterIP and removing the
# old nodePort happen atomically; all existing selectors and port targets stay.
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
kubectl -n kuboard patch service/headlamp --type=json \
  --patch-file "$script_dir/headlamp-service-clusterip-patch.json"
kubectl -n kuboard patch deployment/headlamp --type strategic \
  --patch-file "$script_dir/headlamp-base-url-patch.yaml"
