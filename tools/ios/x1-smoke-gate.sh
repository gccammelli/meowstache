#!/usr/bin/env bash
# Run the private iPhone smoke with short-lived tunnel and loopback Appium processes.
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/../.." && pwd)
[[ $# -le 1 ]] || { echo "Usage: $0 [meowstache|pandy|liberpet]" >&2; exit 2; }
app=${1:-meowstache}
[[ $app == meowstache || $app == pandy || $app == liberpet ]] || {
  echo "Usage: $0 [meowstache|pandy|liberpet]" >&2
  exit 2
}
private="${IOS_PRIVATE_DIR:-$HOME/secrets/max/apple}"
runtime="$project_dir/build/ios-private"
appium="$project_dir/build/ios-tools/node/node_modules/.bin/appium"
python="$project_dir/build/ios-tools/python/bin/python"
root_port=/root/.local/share/appium-xcuitest-driver-nodejs/strongbox/tunnelRegistryPort
user_port="$HOME/.local/share/appium-xcuitest-driver-nodejs/strongbox/tunnelRegistryPort"

mkdir -p -m 700 "$runtime"
exec 9>"$runtime/x1-smoke.lock"
flock -n 9 || exit 0
[[ $(cat "$private/iphone-smoke-enabled" 2>/dev/null || true) == true ]] || exit 0

if ! bash "$project_dir/tools/ios/doctor.sh"; then
  echo "iPhone is not ready; scheduled smoke skipped"
  exit 0
fi

tunnel_pid=
tunnel_launcher_pid=
tunnel_pid_file="$runtime/tunnel.pid"
appium_pid=
cleanup() {
  if [[ -n "$appium_pid" ]]; then
    kill "$appium_pid" 2>/dev/null || true
    wait "$appium_pid" 2>/dev/null || true
  fi
  if [[ -n "$tunnel_pid" ]]; then
    sudo -n kill "$tunnel_pid" 2>/dev/null || true
  fi
  if [[ -n "$tunnel_launcher_pid" ]]; then
    wait "$tunnel_launcher_pid" 2>/dev/null || true
  fi
  sudo -n rm -f "$root_port" "$tunnel_pid_file"
  rm -f "$user_port"
}
trap cleanup EXIT INT TERM

sudo -n rm -f "$root_port" "$tunnel_pid_file"
sudo -n sh -c 'echo "$$" > "$1"; exec env APPIUM_HOME="$2" "$3" driver run xcuitest tunnel-creation' \
  sh "$tunnel_pid_file" "$project_dir/build/ios-tools/appium" "$appium" \
  >"$runtime/tunnel.log" 2>&1 &
tunnel_launcher_pid=$!
for _ in {1..45}; do
  sudo -n test -s "$root_port" && sudo -n test -s "$tunnel_pid_file" && break
  kill -0 "$tunnel_launcher_pid" 2>/dev/null || { echo "RemoteXPC tunnel stopped during startup"; exit 1; }
  sleep 1
done
sudo -n test -s "$root_port" && sudo -n test -s "$tunnel_pid_file" ||
  { echo "RemoteXPC tunnel did not start"; exit 1; }
tunnel_pid=$(sudo -n cat "$tunnel_pid_file")
[[ $tunnel_pid =~ ^[0-9]+$ ]] || { echo "RemoteXPC tunnel PID is invalid"; exit 1; }

mkdir -p -m 700 "$(dirname "$user_port")"
sudo -n cp "$root_port" "$user_port"
sudo -n chown "$(id -u):$(id -g)" "$user_port"
chmod 600 "$user_port"
registry_port=$(cat "$user_port")
[[ $registry_port =~ ^[0-9]+$ ]] || { echo "RemoteXPC registry port is invalid"; exit 1; }
for _ in {1..45}; do
  curl --silent --fail "http://127.0.0.1:$registry_port/remotexpc/tunnels" |
    jq --exit-status '.tunnels | length > 0' >/dev/null 2>&1 && break
  sudo -n kill -0 "$tunnel_pid" 2>/dev/null || { echo "RemoteXPC tunnel stopped during setup"; exit 1; }
  sleep 1
done
curl --silent --fail "http://127.0.0.1:$registry_port/remotexpc/tunnels" |
  jq --exit-status '.tunnels | length > 0' >/dev/null 2>&1 ||
  { echo "RemoteXPC tunnel did not become ready"; exit 1; }

APPIUM_HOME="$project_dir/build/ios-tools/appium" \
  "$appium" --address 127.0.0.1 --port 4723 >"$runtime/appium.log" 2>&1 &
appium_pid=$!
for _ in {1..30}; do
  curl --silent --fail http://127.0.0.1:4723/status >/dev/null && break
  kill -0 "$appium_pid" 2>/dev/null || { echo "Appium stopped during startup"; exit 1; }
  sleep 1
done
curl --silent --fail http://127.0.0.1:4723/status >/dev/null ||
  { echo "Appium did not become ready"; exit 1; }

"$python" "$project_dir/tools/ios/smoke.py" "$app"
