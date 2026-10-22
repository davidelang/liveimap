#!/bin/sh
# Recreate (or reset: the same thing) one lab server, or every lab server, from its seed image.
#   tools/test-servers/start.sh --recreate <server> | --reset <server> | --recreate-all
# Reset = remove the copy-on-write container and create a fresh one from liveimap-seed-<server>.
# Host ports are shared by all lab servers (generated/lab-ports.tsv, drawn once by lab_ports.py and never
# redrawn); each server maps them to its own container ports from servers.tsv. Only one lab server may run:
# this script refuses while another one is running. The new container is started once, checked (published
# ports, no mounts) and left stopped.
set -eu
SCRIPT=$(readlink -f "$0")
DIR=$(dirname "$SCRIPT")

usage() {
  echo "ERROR: usage: tools/test-servers/start.sh --recreate|--reset <server> | --recreate-all" >&2
  exit 1
}

seed_servers() {
  python3 -c 'import sys; sys.path.insert(0, sys.argv[1]); import lab; sys.stdout.write("\n".join(lab.SEED_SERVERS))' "$DIR"
}

if [ $# -eq 1 ] && [ "$1" = --recreate-all ]; then
  servers=$(seed_servers)
  for server in $servers; do
    "$SCRIPT" --recreate "$server" || exit 1
  done
  exit 0
fi
[ $# -eq 2 ] || usage
case "$1" in --recreate|--reset) ;; *) usage ;; esac
server=$2
servers=$(seed_servers)
case " $(echo $servers) " in *" $server "*) ;; *) echo "ERROR: $server is not a lab server" >&2; exit 1 ;; esac
name="liveimap-$server"
image="liveimap-seed-$server"

running=$(docker ps --format '{{.Names}}')
for other in $servers; do
  if [ "$other" != "$server" ] && printf '%s\n' "$running" | grep -qx "liveimap-$other"; then
    echo "ERROR: liveimap-$other is running; one lab server at a time: docker stop liveimap-$other" >&2
    exit 1
  fi
done
if ! docker image inspect "$image" >/dev/null; then
  echo "ERROR: no $image: tools/test-servers/build-seed-image $server" >&2
  exit 1
fi

python3 "$DIR/lab_ports.py" sync
port_flags=$(python3 "$DIR/lab_ports.py" flags "$server")
seed_hostname=$(docker image inspect -f '{{index .Config.Labels "liveimap.hostname"}}' "$image")

if docker container inspect "$name" >/dev/null 2>&1; then
  docker rm -f "$name" >/dev/null
fi
# shellcheck disable=SC2086
docker create --name "$name" ${seed_hostname:+--hostname "$seed_hostname"} $port_flags "$image" >/dev/null
docker start "$name" >/dev/null
python3 - "$name" "$port_flags" <<'PY'
import json, subprocess, sys
name, flags = sys.argv[1], sys.argv[2].split()
want = {}
for i in range(0, len(flags), 2):
    host, cport = flags[i + 1].split(":")
    want[cport + "/tcp"] = host
info = json.loads(subprocess.check_output(["docker", "inspect", name]))[0]
got = {k: v[0]["HostPort"] for k, v in (info["HostConfig"]["PortBindings"] or {}).items()}
problems = []
if got != want:
    problems.append(f"published {got} != {want}")
if info["Mounts"]:
    problems.append(f"mounts {[m.get('Destination') for m in info['Mounts']]}")
if problems:
    subprocess.run(["docker", "stop", name], check=True, capture_output=True)
    sys.exit(f"ERROR: {name}: " + "; ".join(problems))
PY
docker stop "$name" >/dev/null
echo "$name created from $image ($port_flags)"
