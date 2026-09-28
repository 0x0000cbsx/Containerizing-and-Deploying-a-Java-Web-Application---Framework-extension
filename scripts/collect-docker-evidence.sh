#!/usr/bin/env bash
# Local Docker evidence: image build, endpoints, env-based port, concurrency and graceful shutdown.
# Usage (repo root, Docker running): ./scripts/collect-docker-evidence.sh
set -uo pipefail
cd "$(dirname "$0")/.."
IMAGE=${IMAGE:-0x0cbsx/framework-extension:1.0}
OUT=docs/evidence/local-docker.txt

run() { echo "\$ $*"; "$@" 2>&1; echo; }
section() { printf '\n########## %s ##########\n\n' "$1"; }

docker rm -f fw-1 fw-2 >/dev/null 2>&1
{
  section "1. Build image (multi-stage: Maven + Corretto 21)"
  echo "\$ docker build -t $IMAGE ."
  docker build -q -t "$IMAGE" . 2>&1
  echo
  run docker images "${IMAGE%%:*}"

  section "2. Run container, port taken from PORT"
  run docker run -d --name fw-1 -e PORT=8080 -e GREETING_PREFIX=Hola -p 35000:8080 "$IMAGE"
  sleep 3
  run docker ps --filter name=fw-
  run curl -s "http://localhost:35000/greeting?name=Docker"
  run curl -s "http://localhost:35000/hello?name=Docker"
  run curl -s "http://localhost:35000/config"
  run curl -s -o /dev/null -w "%{http_code} %{content_type}\n" "http://localhost:35000/"

  section "3. Same image, different PORT inside the container"
  run docker run -d --name fw-2 -e PORT=9090 -p 35001:9090 "$IMAGE"
  sleep 3
  run curl -s "http://localhost:35001/greeting?name=Port9090"
  run docker logs fw-2

  section "4. Concurrency: 5 requests of 2 s each, in parallel"
  echo '$ time (for i in 1 2 3 4 5; do curl -s "http://localhost:35000/slow?ms=2000" & done; wait)'
  start=$(date +%s.%N)
  tmp=$(mktemp -d)
  for i in 1 2 3 4 5; do curl -s -w "\n" "http://localhost:35000/slow?ms=2000" > "$tmp/$i" & done; wait
  cat "$tmp"/*; rm -rf "$tmp"
  echo "total: $(echo "$(date +%s.%N) - $start" | bc) s  (sequential would be ~10 s)"
  echo
  echo '$ curl -s "/slow?ms=3000" &  then  curl -s "/greeting"   # fast request is not blocked'
  curl -s "http://localhost:35000/slow?ms=3000" >/dev/null & sleep 0.3
  curl -s -o /dev/null -w "fast /greeting answered in %{time_total} s while /slow was running\n" "http://localhost:35000/greeting"
  wait
  echo
  run docker logs --tail 12 fw-1

  section "5. Graceful shutdown: docker stop while a 4 s request is in flight"
  echo '$ curl "/slow?ms=4000" &  sleep 1;  time docker stop fw-1'
  curl -s -w "  <- in-flight request completed (HTTP %{http_code})\n" "http://localhost:35000/slow?ms=4000" &
  sleep 1
  s=$(date +%s.%N); docker stop fw-1; echo "docker stop took $(echo "$(date +%s.%N) - $s" | bc) s"
  wait
  echo
  echo '$ curl "/greeting"   # after stop: connection refused'
  curl -s -m 3 "http://localhost:35000/greeting" || echo "curl exit code $? (connection refused)"
  echo
  run docker inspect -f 'exit code: {{.State.ExitCode}}' fw-1
  run docker logs --tail 6 fw-1

  docker rm -f fw-1 fw-2 >/dev/null
} | tee "$OUT"
echo "Evidence saved to $OUT"
