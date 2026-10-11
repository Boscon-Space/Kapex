#!/bin/bash
# Runs ON THE PI (nohup). Build llama.cpp natively, fetch the primary model,
# run the C++ baseline benchmark. Log: ~/llama/setup.log
set -uo pipefail
mkdir -p ~/llama ~/models; cd ~/llama
MODEL_URL=https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
MODEL=~/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
echo "== $(date) start"
# model download in the background while we compile
( [ -f "$MODEL" ] || curl -sS -L -o "$MODEL.part" "$MODEL_URL" && mv "$MODEL.part" "$MODEL"; echo "== $(date) model: $(ls -l $MODEL 2>/dev/null | awk '{print $5}') bytes" ) &
if [ ! -d src ]; then git clone -q --depth 1 https://github.com/ggml-org/llama.cpp.git src; fi
cd src; echo "== llama.cpp commit $(git rev-parse --short HEAD) $(git log -1 --format=%cd --date=short)"
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DBUILD_SHARED_LIBS=ON > ../cmake.log 2>&1 || { echo "!! cmake failed"; tail -20 ../cmake.log; }
cmake --build build --config Release -j4 --target llama llama-bench llama-cli > ../build.log 2>&1 && echo "== $(date) build ok" || { echo "!! build failed"; tail -30 ../build.log; }
cmake --install build --prefix ~/llama/install > ../install.log 2>&1 && echo "== installed to ~/llama/install: $(ls ~/llama/install/lib | tr '\n' ' ')"
wait
echo "== $(date) baseline bench (C++ llama-bench, 4 threads, 128 tokens):"
LD_LIBRARY_PATH=~/llama/install/lib ~/llama/install/bin/llama-bench -m "$MODEL" -t 4 -p 64 -n 128 -r 2 2>&1 | tail -8
echo "== $(date) done"
