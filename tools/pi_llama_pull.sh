#!/bin/bash
# Copy llama.cpp headers + shared libraries from the Pi's build into kapex/llm/native
# so the Mac can cinterop and link against them for linuxArm64.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$HERE/llm/native/include" "$HERE/llm/native/lib"
"$HERE/tools/pi.sh" 'cd ~/llama/install && tar cf - include lib' | tar xf - -C "$HERE/llm/native"
# cmake --install ships neither llama.h nor libllama.so; copy them into the install tree on the Pi first
"$HERE/tools/pi.sh" 'cp -a ~/llama/src/build/bin/libllama.so* ~/llama/install/lib/; cp ~/llama/src/include/llama*.h ~/llama/install/include/'
# (headers also fetched directly below in case the install tree is stale)
"$HERE/tools/pi.sh" 'cat ~/llama/src/include/llama.h' > "$HERE/llm/native/include/llama.h"
"$HERE/tools/pi.sh" 'cat ~/llama/src/include/llama-cpp.h' > "$HERE/llm/native/include/llama-cpp.h"
ls "$HERE/llm/native/include" | head; ls -l "$HERE/llm/native/lib"
