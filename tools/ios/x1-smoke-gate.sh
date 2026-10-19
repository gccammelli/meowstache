#!/usr/bin/env bash
# Run the private iPhone smoke with short-lived tunnel and loopback Appium processes.
set -euo pipefail

project_dir=$(cd "$(dirname "$0")/../.." && pwd)
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
appium_pid=
cleanup() {
  if [[ -n "$appium_pid" ]]; then
    kill "$appium_pid" 2>/dev/null || true
    wait "$appium_pid" 2>/dev/null || true
  fi
  if [[ -n "$tunnel_pid" ]]; then
    sudo -n kill "$tunnel_pid" 2>/dev/null || true
    wait "$tunnel_pid" 2>/dev/null || true
  fi
  sudo -n rm -f "$root_port"
  rm -f "$user_port"
}
trap cleanup EXIT INT TERM

sudo -n rm -f "$root_port"
sudo -n env APPIUM_HOME="$project_dir/build/ios-tools/appium" \
  "$appium" driver run xcuitest tunnel-creation >"$runtime/tunnel.log" 2>&1 &
tunnel_pid=$!
for _ in {1..45}; do
  sudo -n test -s "$root_port" && break
  kill -0 "$tunnel_pid" 2>/dev/null || { echo "RemoteXPC tunnel stopped during startup"; exit 1; }
  sleep 1
done
sudo -n test -s "$root_port" || { echo "RemoteXPC tunnel did not become ready"; exit 1; }

mkdir -p -m 700 "$(dirname "$user_port")"
sudo -n cp "$root_port" "$user_port"
sudo -n chown "$(id -u):$(id -g)" "$user_port"
chmod 600 "$user_port"

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

"$python" "$project_dir/tools/ios/smoke.py"
