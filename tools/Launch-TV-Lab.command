#!/bin/bash
# Finder double-click entry point; leaves account/device data intact.
cd "$(dirname "$0")/.." || exit 1
umask 077
mkdir -p .toolcache/tv-lab
chmod 700 .toolcache/tv-lab
bash tools/tv-lab.sh launch 2>&1 | /usr/bin/tee .toolcache/tv-lab/launch.log
result=${PIPESTATUS[0]}
if [[ $result -ne 0 ]]; then
    echo "启动失败，请查看上方提示和 .toolcache/tv-lab/emulator.log。"
    read -r -p "按回车关闭…" reply
fi
exit "$result"
