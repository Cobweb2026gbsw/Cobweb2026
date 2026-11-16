#!/bin/sh
# Runs inside a throwaway judge container under the same resource and security
# options as a submission. Only the trusted build's own fixture is executed.
set -eu

check=identity
trap 'status=$?; [ "$status" -eq 0 ] || printf "[Cobweb judge] Sandbox check failed: %s\n" "$check" >&2' EXIT
test "$(id -u):$(id -g)" = '65534:65534'
check=privileges
awk '
    /^CapEff:/ && $2 == "0000000000000000" { cap = 1 }
    /^NoNewPrivs:/ && $2 == 1 { nnp = 1 }
    /^Seccomp:/ && $2 == 2 { seccomp = 1 }
    END { exit !(cap && nnp && seccomp) }
' /proc/self/status

# Check effective controls rather than only verifying flags in Docker metadata.
check=network
test -d /sys/class/net/lo
for interface in /sys/class/net/*; do
    # Linux can expose dormant kernel tunnel devices even in an unnetworked
    # namespace. Loopback must be the only interface with IFF_UP set.
    [ "$interface" != /sys/class/net/lo ] && [ -r "$interface/flags" ] || continue
    flags=$(cat "$interface/flags")
    test "$((flags & 1))" -eq 0
done
awk 'NR > 1 && $1 != "lo" { routes = 1 } END { exit routes }' /proc/net/route
if [ -e /proc/net/ipv6_route ]; then
    awk '$NF != "lo" { routes = 1 } END { exit routes }' /proc/net/ipv6_route
fi

check=mounts
awk '$2 == "/" && $4 ~ /^ro(,|$)/ { enabled = 1 } END { exit !enabled }' /proc/mounts
awk '$2 == "/tmp" && $3 == "tmpfs" && $4 ~ /(^|,)noexec(,|$)/ && $4 ~ /(^|,)nosuid(,|$)/ { enabled = 1 }
     $2 == "/artifact" && $3 == "tmpfs" { artifacts = 1 }
     END { exit !(enabled && artifacts) }' /proc/mounts
check=resource-limits
test "$(cat /sys/fs/cgroup/memory.max)" = '134217728'
test "$(cat /sys/fs/cgroup/memory.swap.max)" = '0'
test "$(cat /sys/fs/cgroup/pids.max)" = '128'
awk 'NF == 2 && $1 ~ /^[0-9]+$/ && $2 > 0 && $1 == $2 { enabled = 1 } END { exit !enabled }' /sys/fs/cgroup/cpu.max
awk '/^Max cpu time/ && $4 == 5 && $5 == 6 { cpu = 1 }
     /^Max file size/ && $4 == 67108864 && $5 == 67108864 { file = 1 }
     END { exit !(cpu && file) }' /proc/self/limits

check=artifact
test ! -S /run/cobweb-docker/docker.sock
printf '%s\n' 'cobweb-fixture' > /tmp/cobweb-fixture
cp /tmp/cobweb-fixture /artifact/startup-check
