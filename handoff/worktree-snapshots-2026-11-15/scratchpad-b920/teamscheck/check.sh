set -u
run() {
  echo "== $1"
  ( export SANGAM_TEAMS_ENABLED="$2" SANGAM_TEAMS_FRAME_ANCESTORS="$3"
    /docker-entrypoint.sh nginx -t >/tmp/out 2>&1; rc=$?; grep -E 'ERROR|emerg|test is successful' /tmp/out; echo "rc=$rc"
    if [ $rc -eq 0 ]; then
      grep -o '"teams":{[^}]*}' /etc/nginx/conf.d/default.conf
      grep -A3 'location = /teams.html' /etc/nginx/conf.d/default.conf | grep include
      grep -o 'frame-ancestors [^;]*' /etc/nginx/conf.d/teams-headers.inc
      grep -c 'X-Frame-Options\|Cross-Origin-Opener' /etc/nginx/conf.d/teams-headers.inc
    fi )
}
run disabled-default "" ""
run enabled-default true ""
run enabled-custom true "https://teams.example.org https://*.cloud.microsoft:443"
run bad-http true "http://a.example"
run bad-quote true "https://a.example 'self'"
run bad-false-word false "https://ok.example"