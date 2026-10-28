#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
# Fixed allow-list. Never eval model output or pass model-generated strings to a shell.
TOOL="${1:-}"
ARGS="${2:-{}}"
ROOT="$HOME/omni-tools"
MODEL="$HOME/storage/downloads/OmniTermuxAssistant/active-model.gguf"
MMPROJ="$HOME/storage/downloads/OmniTermuxAssistant/active-mmproj.gguf"
SERVER="$HOME/bin/llama-server"
mkdir -p "$HOME/bin" "$ROOT"
case "$TOOL" in
  start_local_model)
    if [ ! -x "$SERVER" ]; then
      RUNTIME="$HOME/storage/downloads/OmniTermuxAssistant/llama-runtime.zip"
      if [ -r "$RUNTIME" ]; then
        TMP="$HOME/omni-tools/runtime-extract"
        rm -rf "$TMP" && mkdir -p "$TMP"
        unzip -oq "$RUNTIME" -d "$TMP"
        if [ -x "$TMP/bin/llama-server" ]; then
          install -m 700 "$TMP/bin/llama-server" "$SERVER"
        fi
        rm -rf "$TMP"
      fi
    fi
    if [ ! -x "$SERVER" ]; then
      echo "llama-server is not installed. Ensure llama-runtime.zip is present in Download/OmniTermuxAssistant/."
      exit 3
    fi
    if [ ! -r "$MODEL" ]; then echo "Model missing: $MODEL" >&2; exit 4; fi
    if [ ! -r "$MMPROJ" ]; then echo "mmproj missing: $MMPROJ (required for LFM3-Omni multimodal use)" >&2; exit 5; fi
    if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
      echo "Local llama.cpp server already running on 127.0.0.1:8080"; exit 0
    fi
    nohup "$SERVER" -m "$MODEL" --mmproj "$MMPROJ" --host 127.0.0.1 --port 8080 --no-webui --reasoning off -c 4096 > "$ROOT/server.log" 2>&1 < /dev/null &
    for _ in $(seq 1 90); do
      if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then echo "Local LFM3-Omni server ready on 127.0.0.1:8080"; exit 0; fi
      sleep 1
    done
    echo "Server did not become ready. Check $ROOT/server.log" >&2
    exit 6
    ;;
  stop_local_model)
    if curl -fsS -X POST http://127.0.0.1:8080/shutdown >/dev/null 2>&1; then echo "Shutdown requested"; else echo "No server shutdown endpoint; stop llama-server from Termux."; fi
    ;;
  web_search)
    QUERY="$(printf '%s' "$ARGS" | python -c 'import json,sys; print(json.load(sys.stdin)["query"])')"
    printf 'No search provider configured. Query: %s\n' "$QUERY"
    ;;
  send_sms)
    NUMBER="$(printf '%s' "$ARGS" | python -c 'import json,sys; print(json.load(sys.stdin)["number"])')"
    MESSAGE="$(printf '%s' "$ARGS" | python -c 'import json,sys; print(json.load(sys.stdin)["message"])')"
    termux-sms-send -n "$NUMBER" "$MESSAGE"
    ;;
  *) echo "Unknown or disallowed tool" >&2; exit 2 ;;
esac
