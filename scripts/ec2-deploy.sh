#!/usr/bin/env bash
# Run INSIDE the EC2 instance (Amazon Linux 2023).
#   bash ec2-deploy.sh install   -> installs Docker (then log out and back in)
#   bash ec2-deploy.sh run       -> runs the image on host port 8081 and saves evidence in ~/ec2-evidence.txt
# The image must be present on the instance: docker pull it from a registry, or copy it from your machine:
#   docker save 0x0cbsx/framework-extension:1.0 | gzip | ssh ec2-user@<host> 'gunzip | docker load'
set -uo pipefail
IMAGE=${IMAGE:-0x0cbsx/framework-extension:1.0}
HOST_PORT=${HOST_PORT:-8081}
URL="http://localhost:$HOST_PORT"

case "${1:-}" in
  install)
    sudo dnf install -y docker
    sudo systemctl enable --now docker
    sudo usermod -a -G docker ec2-user
    echo "Done. Log out, reconnect over SSH and run: bash ec2-deploy.sh run"
    ;;
  run)
    run() { echo "\$ $*"; "$@" 2>&1; echo; }
    docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull "$IMAGE"
    docker rm -f framework >/dev/null 2>&1
    start_container() {
      docker run -d --name framework --restart unless-stopped \
        -e PORT=8080 -e APP_ENV=production -e "GREETING_PREFIX=Hello from EC2" \
        -p "$HOST_PORT":8080 "$IMAGE"
    }
    {
      run grep PRETTY_NAME /etc/os-release
      run docker --version
      run docker images "${IMAGE%%:*}"
      echo "\$ docker run -d --name framework --restart unless-stopped -e PORT=8080 -e APP_ENV=production -e \"GREETING_PREFIX=Hello from EC2\" -p $HOST_PORT:8080 $IMAGE"
      start_container; echo
      sleep 3
      run docker ps
      run curl -s "$URL/greeting?name=AWS"
      run curl -s "$URL/hello?name=AWS"
      run curl -s "$URL/config"

      echo "########## Concurrency on EC2: 5 x 2 s requests in parallel ##########"
      tmp=$(mktemp -d); s=$(date +%s%N)
      for i in 1 2 3 4 5; do curl -s -w "\n" "$URL/slow?ms=2000" > "$tmp/$i" & done; wait
      cat "$tmp"/*; rm -rf "$tmp"
      echo "total: $(( ($(date +%s%N) - s) / 1000000 )) ms (sequential would be ~10000 ms)"
      echo

      echo "########## Graceful shutdown on EC2: docker stop with a 4 s request in flight ##########"
      curl -s -w "  <- in-flight request completed (HTTP %{http_code})\n" "$URL/slow?ms=4000" &
      sleep 1
      s=$(date +%s%N); docker stop framework >/dev/null; echo "docker stop took $(( ($(date +%s%N) - s) / 1000000 )) ms"
      wait
      run docker logs --tail 5 framework

      echo "########## Start the service again ##########"
      docker rm framework >/dev/null
      start_container >/dev/null; sleep 3
      run docker ps
      run curl -s "$URL/greeting?name=AWS"
      TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
      echo "Public URL: http://$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-hostname):$HOST_PORT/greeting?name=AWS"
    } | tee ~/ec2-evidence.txt
    ;;
  *) echo "Usage: $0 install|run"; exit 1 ;;
esac
