#!/usr/bin/env bash
set -euo pipefail

# Apply the checked-in strategic patch to the existing Headlamp deployment.
# The script is intentionally separate from the misu-server release: Headlamp
# is owned by the kuboard installation and its existing Service/NodePort stays
# untouched.
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
kubectl -n kuboard patch deployment/headlamp --type strategic \
  --patch-file "$script_dir/headlamp-base-url-patch.yaml"
