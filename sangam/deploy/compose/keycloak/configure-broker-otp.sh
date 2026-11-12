#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Owner decision 113: after a brokered (SAML) sign-in, Keycloak asks for a one-time password, and the token says so in
# its `amr` claim, which the claims profile's requireMfa accepts (otp). Runs after the realm import with kcadm, on
# every start; every step checks first and changes only what differs, so a second run changes nothing. The realm
# import (generate_realm.py, realm/*.json) is not touched.
#
#   1. flow   $FLOW_ALIAS (top level, basic-flow) with one execution: OTP Form (auth-otp-form), REQUIRED. Its
#             authenticator config sets the AMR reference value `otp` (default.reference.value) and its max age.
#             A user without an OTP credential is sent to the CONFIGURE_TOTP required action: enrolment at first
#             sign-in. The flow holds no username or password step: the broker has already identified the user.
#   2. broker each alias in $BROKER_ALIASES gets postBrokerLoginFlowAlias=$FLOW_ALIAS (after every broker sign-in,
#             not only the first). Its other settings and the first-broker-login flow are left as imported.
#   3. CONFIGURE_TOTP required action registered and enabled.
#   4. mapper `amr` (oidc-amr-mapper) on each client in $AMR_CLIENTS (ID token, access token, introspection). The
#             script stops if the server has no such mapper type.
#
# Admin credentials come only from the environment or a secret file and are never printed:
#   KEYCLOAK_URL               default http://keycloak:8180
#   KEYCLOAK_ADMIN_REALM       default master
#   KEYCLOAK_ADMIN             admin user (required)
#   KEYCLOAK_ADMIN_PASSWORD_FILE  file holding the admin password (preferred), or
#   KEYCLOAK_ADMIN_PASSWORD    the admin password
#   SANGAM_REALM               default sangam
#   BROKER_ALIASES             default gov-sso-saml (space separated, e.g. "gov-sso-saml gov-sso-saml-t1")
#   AMR_CLIENTS                default sangam-ui (space separated client ids that receive brokered sign-ins)
#   FLOW_ALIAS                 default sangam-broker-otp
#   OTP_AMR_MAX_AGE            default 36000 (seconds an OTP counts in `amr`; the realm's SSO session maximum)
#   KCADM                      default /opt/keycloak/bin/kcadm.sh (run it in the Keycloak image)
#   WAIT_SECONDS               default 300 (how long to wait for Keycloak)
set -eu

KEYCLOAK_URL=${KEYCLOAK_URL:-http://keycloak:8180}
KEYCLOAK_ADMIN_REALM=${KEYCLOAK_ADMIN_REALM:-master}
SANGAM_REALM=${SANGAM_REALM:-sangam}
BROKER_ALIASES=${BROKER_ALIASES:-gov-sso-saml}
AMR_CLIENTS=${AMR_CLIENTS:-sangam-ui}
FLOW_ALIAS=${FLOW_ALIAS:-sangam-broker-otp}
OTP_AMR_MAX_AGE=${OTP_AMR_MAX_AGE:-36000}
KCADM=${KCADM:-/opt/keycloak/bin/kcadm.sh}
WAIT_SECONDS=${WAIT_SECONDS:-300}
CONFIG_ALIAS="${FLOW_ALIAS}-amr"
OTP=auth-otp-form

log() { printf '%s\n' "configure-broker-otp: $*"; }
# true when the newline-separated list $1 holds the line $2 (shell only: the Keycloak image may ship without grep)
has_line() {
  case "
$1
" in *"
$2
"*) return 0;; esac
  return 1
}
# true when $1 contains $2, ignoring blanks and newlines
contains() {
  case "$(printf '%s' "$1" | tr -d ' \n\r\t')" in *"$2"*) return 0;; esac
  return 1
}
fail() { printf '%s\n' "configure-broker-otp: ERROR: $*" >&2; exit 1; }

case "$FLOW_ALIAS" in *[!A-Za-z0-9._-]*|'') fail "FLOW_ALIAS may hold letters, digits, '.', '_' and '-' only";; esac
case "$OTP_AMR_MAX_AGE" in *[!0-9]*|'') fail "OTP_AMR_MAX_AGE must be a number of seconds";; esac
[ -n "${KEYCLOAK_ADMIN:-}" ] || fail "KEYCLOAK_ADMIN is not set"
if [ -n "${KEYCLOAK_ADMIN_PASSWORD_FILE:-}" ]; then
  [ -r "$KEYCLOAK_ADMIN_PASSWORD_FILE" ] || fail "KEYCLOAK_ADMIN_PASSWORD_FILE is not readable"
  ADMIN_PASSWORD=$(cat "$KEYCLOAK_ADMIN_PASSWORD_FILE")
elif [ -n "${KEYCLOAK_ADMIN_PASSWORD:-}" ]; then
  ADMIN_PASSWORD=$KEYCLOAK_ADMIN_PASSWORD
else
  fail "set KEYCLOAK_ADMIN_PASSWORD_FILE or KEYCLOAK_ADMIN_PASSWORD"
fi

# kcadm keeps its access token in this file; private to this run and removed on exit.
WORK=$(mktemp -d)
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT INT TERM
KC_CONFIG="$WORK/kcadm.config"
kc() { "$KCADM" "$@" --config "$KC_CONFIG"; }
r() { kc "$@" -r "$SANGAM_REALM"; }

# --- sign in (waits for Keycloak and the imported realm); the password is never echoed
waited=0
until "$KCADM" config credentials --config "$KC_CONFIG" --server "$KEYCLOAK_URL" --realm "$KEYCLOAK_ADMIN_REALM" \
    --user "$KEYCLOAK_ADMIN" --password "$ADMIN_PASSWORD" >/dev/null 2>&1 \
    && kc get "realms/$SANGAM_REALM" --fields realm >/dev/null 2>&1; do
  [ "$waited" -lt "$WAIT_SECONDS" ] || fail "Keycloak at $KEYCLOAK_URL (realm $SANGAM_REALM) not reachable after ${WAIT_SECONDS}s"
  sleep 5
  waited=$((waited + 5))
done
unset ADMIN_PASSWORD
log "signed in to $KEYCLOAK_URL, realm $SANGAM_REALM"

# --- 0. the server must know the AMR mapper, or the token would never show the second factor
contains "$(kc get serverinfo 2>/dev/null)" '"oidc-amr-mapper"' \
  || fail "this Keycloak has no oidc-amr-mapper (Authentication Method Reference) protocol mapper"

# --- 1. the flow with one REQUIRED OTP form
if ! has_line "$(r get authentication/flows --fields alias --format csv --noquotes)" "$FLOW_ALIAS"; then
  r create authentication/flows -s "alias=$FLOW_ALIAS" -s providerId=basic-flow -s topLevel=true -s builtIn=false \
    -s "description=Sangam: one-time password after a brokered sign-in (owner decision 113)" >/dev/null
  log "created flow $FLOW_ALIAS"
fi
providers=$(r get "authentication/flows/$FLOW_ALIAS/executions" --fields providerId --format csv --noquotes)
if [ -z "$providers" ]; then
  r create "authentication/flows/$FLOW_ALIAS/executions/execution" -s "provider=$OTP" >/dev/null
  log "added $OTP to $FLOW_ALIAS"
  providers=$(r get "authentication/flows/$FLOW_ALIAS/executions" --fields providerId --format csv --noquotes)
fi
[ "$providers" = "$OTP" ] \
  || fail "flow $FLOW_ALIAS must hold exactly one execution ($OTP); found: $(printf '%s' "$providers" | tr '\n' ' ')"
exec_id=$(r get "authentication/flows/$FLOW_ALIAS/executions" --fields id --format csv --noquotes)
requirement=$(r get "authentication/flows/$FLOW_ALIAS/executions" --fields requirement --format csv --noquotes)
if [ "$requirement" != "REQUIRED" ]; then
  r update "authentication/flows/$FLOW_ALIAS/executions" -b "{\"id\":\"$exec_id\",\"requirement\":\"REQUIRED\"}"
  log "$OTP in $FLOW_ALIAS set to REQUIRED"
fi
config_id=$(r get "authentication/flows/$FLOW_ALIAS/executions" --fields authenticationConfig --format csv --noquotes)
config_body="\"alias\":\"$CONFIG_ALIAS\",\"config\":{\"default.reference.value\":\"otp\",\"default.reference.maxAge\":\"$OTP_AMR_MAX_AGE\"}"
if [ -z "$config_id" ]; then
  r create "authentication/executions/$exec_id/config" -b "{$config_body}" >/dev/null
  log "AMR reference otp set on $OTP"
else
  current=$(r get "authentication/config/$config_id")
  if ! contains "$current" '"default.reference.value":"otp"' \
     || ! contains "$current" "\"default.reference.maxAge\":\"$OTP_AMR_MAX_AGE\""; then
    r update "authentication/config/$config_id" -b "{\"id\":\"$config_id\",$config_body}"
    log "AMR reference of $OTP corrected"
  fi
fi

# --- 2. bind the flow as post-broker-login flow of each broker
for alias in $BROKER_ALIASES; do
  bound=$(r get "identity-provider/instances/$alias" --fields postBrokerLoginFlowAlias --format csv --noquotes) \
    || fail "identity provider $alias not found in realm $SANGAM_REALM"
  if [ "$bound" != "$FLOW_ALIAS" ]; then
    r update "identity-provider/instances/$alias" -s "postBrokerLoginFlowAlias=$FLOW_ALIAS"
    log "broker $alias: post-broker-login flow $FLOW_ALIAS"
  fi
done

# --- 3. enrolment at first sign-in: the CONFIGURE_TOTP required action, registered and enabled
if ! has_line "$(r get authentication/required-actions --fields alias --format csv --noquotes)" CONFIGURE_TOTP; then
  r create authentication/register-required-action -s providerId=CONFIGURE_TOTP -s "name=Configure OTP" >/dev/null
  log "registered required action CONFIGURE_TOTP"
fi
if [ "$(r get authentication/required-actions/CONFIGURE_TOTP --fields enabled --format csv --noquotes)" != "true" ]; then
  r update authentication/required-actions/CONFIGURE_TOTP -s enabled=true
  log "enabled required action CONFIGURE_TOTP"
fi

# --- 4. the amr claim on each client that receives brokered sign-ins
for client in $AMR_CLIENTS; do
  cid=$(r get clients -q "clientId=$client" --fields id --format csv --noquotes)
  [ -n "$cid" ] || fail "client $client not found in realm $SANGAM_REALM"
  if ! has_line "$(r get "clients/$cid/protocol-mappers/models" --fields name --format csv --noquotes)" amr; then
    r create "clients/$cid/protocol-mappers/models" -s name=amr -s protocol=openid-connect \
      -s protocolMapper=oidc-amr-mapper -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' \
      -s 'config."introspection.token.claim"=true' >/dev/null
    log "client $client: amr mapper added"
  fi
done

log "done (flow $FLOW_ALIAS on brokers: $BROKER_ALIASES; amr on clients: $AMR_CLIENTS)"
