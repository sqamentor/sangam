set -u
run() {
  echo "== $1"
  ( export SANGAM_TEAMS_ENABLED="$2" SANGAM_DSC_AGENT_ORIGIN="$3" SANGAM_TEAMS_FRAME_ANCESTORS=""
    /docker-entrypoint.sh nginx -t >/tmp/out 2>&1; rc=$?; grep -E 'ERROR|emerg|test is successful' /tmp/out; echo "rc=$rc"
    if [ $rc -eq 0 ]; then
      grep -o '"dsc":{[^}]*}' /etc/nginx/conf.d/default.conf
      grep -o 'connect-src [^;]*' /etc/nginx/conf.d/security-headers.inc /etc/nginx/conf.d/teams-headers.inc
    fi )
}
run unset false ""
run ipv4 true "http://127.0.0.1:47321"
run localhost false "http://localhost:65535"
run ipv6 false "http://[::1]:47321"
run path false "http://127.0.0.1:47321/x"
run https false "https://127.0.0.1:47321"
run port0 false "http://127.0.0.1:0"
run port-big false "http://127.0.0.1:65536"
run upper false "http://LOCALHOST:1"
run newline false "http://127.0.0.1:1
http://127.0.0.1:2"
run inject false "http://127.0.0.1:1 https://evil.example"