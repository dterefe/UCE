#!/bin/sh
set -eu

if [ -z "${KC_BASE_URL:-}" ]; then
  echo "KC_BASE_URL is required (e.g. http://uce-keycloak-auth:8080)"
  exit 1
fi
if [ -z "${KC_ADMIN_USERNAME:-}" ] || [ -z "${KC_ADMIN_PW:-}" ]; then
  echo "KC_ADMIN_USERNAME and KC_ADMIN_PW are required"
  exit 1
fi
if [ -z "${KC_REALM:-}" ]; then
  echo "KC_REALM is required (e.g. uce)"
  exit 1
fi
if [ -z "${KC_CLIENT_ID:-}" ]; then
  echo "KC_CLIENT_ID is required (e.g. uce-web)"
  exit 1
fi
if [ -z "${UCE_PUBLIC_URL:-}" ]; then
  echo "UCE_PUBLIC_URL is required (e.g. https://uce.example.org)"
  exit 1
fi

echo "Waiting for Keycloak at ${KC_BASE_URL}..."
KC_MGMT_URL="${KC_MGMT_URL:-}"
if [ -z "${KC_MGMT_URL}" ]; then
  # Keycloak exposes health endpoints on the management interface (default port 9000).
  # Derive the management URL from KC_BASE_URL (which points to the main HTTP port, usually 8080).
  KC_MGMT_URL="$(echo "${KC_BASE_URL}" | sed -E 's#:[0-9]+$#:9000#')"
fi
for i in $(seq 1 120); do
  if curl -fsS "${KC_MGMT_URL}/health/ready" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "Requesting admin token..."
TOKEN="$(
  curl -fsS \
    -X POST "${KC_BASE_URL}/realms/master/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=admin-cli" \
    --data-urlencode "username=${KC_ADMIN_USERNAME}" \
    --data-urlencode "password=${KC_ADMIN_PW}" \
  | jq -r '.access_token'
)"

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "Failed to obtain access token"
  exit 1
fi

if [ -n "${KC_SSL_REQUIRED:-}" ]; then
  echo "Updating Keycloak realm SSL requirement for ${KC_REALM} (sslRequired=${KC_SSL_REQUIRED})..."
  # Keycloak realm field: sslRequired = none|external|all
  REALM_JSON="$(
    curl -fsS \
      -H "Authorization: Bearer ${TOKEN}" \
      "${KC_BASE_URL}/admin/realms/${KC_REALM}"
  )"

  UPDATED_REALM="$(
    echo "$REALM_JSON" | jq \
      --arg ssl "${KC_SSL_REQUIRED}" \
      '.sslRequired = $ssl'
  )"

  curl -fsS \
    -X PUT "${KC_BASE_URL}/admin/realms/${KC_REALM}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$UPDATED_REALM" \
    >/dev/null

  echo "Keycloak realm SSL requirement updated."
fi

if [ -n "${KC_SSO_SESSION_IDLE_TIMEOUT:-}" ]; then
  echo "Updating Keycloak realm session settings for ${KC_REALM}..."
  REALM_JSON="$(
    curl -fsS \
      -H "Authorization: Bearer ${TOKEN}" \
      "${KC_BASE_URL}/admin/realms/${KC_REALM}"
  )"

  UPDATED_REALM="$(
    echo "$REALM_JSON" | jq \
      --argjson idle "${KC_SSO_SESSION_IDLE_TIMEOUT}" \
      '.ssoSessionIdleTimeout = $idle'
  )"

  curl -fsS \
    -X PUT "${KC_BASE_URL}/admin/realms/${KC_REALM}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$UPDATED_REALM" \
    >/dev/null

  echo "Keycloak realm session settings updated (ssoSessionIdleTimeout=${KC_SSO_SESSION_IDLE_TIMEOUT})."
fi

echo "Resolving client id for realm=${KC_REALM} clientId=${KC_CLIENT_ID}..."
CLIENT_UUID="$(
  curl -fsS \
    -H "Authorization: Bearer ${TOKEN}" \
    "${KC_BASE_URL}/admin/realms/${KC_REALM}/clients?clientId=${KC_CLIENT_ID}" \
  | jq -r '.[0].id'
)"

if [ -z "$CLIENT_UUID" ] || [ "$CLIENT_UUID" = "null" ]; then
  echo "Could not resolve client UUID for ${KC_CLIENT_ID} in realm ${KC_REALM}"
  exit 1
fi

echo "Updating Keycloak client settings for ${KC_CLIENT_ID}..."
CLIENT_JSON="$(
  curl -fsS \
    -H "Authorization: Bearer ${TOKEN}" \
    "${KC_BASE_URL}/admin/realms/${KC_REALM}/clients/${CLIENT_UUID}"
)"

UPDATED="$(
  echo "$CLIENT_JSON" | jq \
    --arg uceUrl "${UCE_PUBLIC_URL}" \
    '
    .rootUrl = $uceUrl
    | .baseUrl = $uceUrl
    | .redirectUris = ([($uceUrl + "/auth/*")] + (.redirectUris // []) | unique)
    | .webOrigins = ([$uceUrl] + (.webOrigins // []) | unique)
    '
)"

curl -fsS \
  -X PUT "${KC_BASE_URL}/admin/realms/${KC_REALM}/clients/${CLIENT_UUID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$UPDATED" \
  >/dev/null

echo "Keycloak client updated."
