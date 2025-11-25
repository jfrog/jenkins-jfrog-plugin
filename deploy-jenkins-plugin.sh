#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   JENKINS_URL=http://localhost:8080 \
#   JENKINS_USER=admin \
#   JENKINS_TOKEN=xxxx \
#   ./deploy-jenkins-plugin.sh path/to/my-plugin.hpi
#
# Optional:
#   DEBUG=1 ./deploy-jenkins-plugin.sh ... (for verbose output)
#
# Notes:
# - Requires: curl, unzip, jq
# - Works for .hpi or .jpi files

# Enable debug mode if DEBUG env var is set
if [[ "${DEBUG:-0}" == "1" ]]; then
  set -x
fi

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 path/to/plugin.hpi|plugin.jpi" >&2
  exit 1
fi

PLUGIN_FILE="$1"

if [[ ! -f "$PLUGIN_FILE" ]]; then
  echo "Plugin file not found: $PLUGIN_FILE" >&2
  exit 1
fi

: "${JENKINS_URL:?JENKINS_URL is required (e.g. http://localhost:8080)}"
: "${JENKINS_USER:?JENKINS_USER is required}"
: "${JENKINS_TOKEN:?JENKINS_TOKEN (or API token) is required}"

# --- 1. Extract plugin short name and version from MANIFEST.MF ---

manifest="$(unzip -p "$PLUGIN_FILE" META-INF/MANIFEST.MF)"

PLUGIN_SHORT_NAME="$(printf "%s\n" "$manifest" | awk -F': ' '/^Short-Name:/ {print $2}')"
PLUGIN_VERSION="$(printf "%s\n" "$manifest" | awk -F': ' '/^Plugin-Version:/ {print $2}')"

if [[ -z "${PLUGIN_SHORT_NAME:-}" || -z "${PLUGIN_VERSION:-}" ]]; then
  echo "Could not determine Short-Name or Plugin-Version from MANIFEST.MF" >&2
  exit 1
fi

echo "Local plugin:"
echo "  Short name : $PLUGIN_SHORT_NAME"
echo "  Version    : $PLUGIN_VERSION"
echo

# --- 2. Verify Jenkins connectivity and authentication ---

echo "Verifying Jenkins connectivity..."
WHO_AM_I_RESP="$(mktemp)"
WHO_AM_I_CODE="$(curl -s -w '%{http_code}' -o "$WHO_AM_I_RESP" \
  -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
  "${JENKINS_URL}/me/api/json" 2>&1)"

if [[ "$WHO_AM_I_CODE" == "200" ]]; then
  USER_FULL_NAME="$(jq -r '.fullName // empty' < "$WHO_AM_I_RESP")"
  if [[ -n "$USER_FULL_NAME" ]]; then
    echo "✅ Connected as: $USER_FULL_NAME"
  else
    echo "✅ Connected (authentication successful)"
  fi
else
  echo "❌ Authentication failed!" >&2
  echo "   HTTP Status: $WHO_AM_I_CODE" >&2
  echo "   URL: ${JENKINS_URL}/me/api/json" >&2
  echo "" >&2
  echo "Please check:" >&2
  echo "  1. JENKINS_URL is correct (currently: $JENKINS_URL)" >&2
  echo "  2. JENKINS_USER is correct (currently: $JENKINS_USER)" >&2
  echo "  3. JENKINS_TOKEN is a valid API token" >&2
  echo "" >&2
  echo "To get an API token:" >&2
  echo "  - Go to: ${JENKINS_URL}/me/configure" >&2
  echo "  - Scroll to 'API Token' section" >&2
  echo "  - Click 'Add new Token' and generate one" >&2
  rm -f "$WHO_AM_I_RESP"
  exit 1
fi
rm -f "$WHO_AM_I_RESP"
echo

# --- 3. Get CSRF crumb (if Jenkins has CSRF protection enabled) ---

echo "Fetching CSRF crumb..."
CRUMB_RESP="$(mktemp)"
CRUMB_CODE="$(curl -s -w '%{http_code}' -o "$CRUMB_RESP" \
  -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
  "${JENKINS_URL}/crumbIssuer/api/json" 2>&1 || echo "000")"

CRUMB_FIELD=""
CRUMB_VALUE=""

if [[ "$CRUMB_CODE" == "200" ]]; then
  CRUMB_JSON="$(cat "$CRUMB_RESP")"
  CRUMB_FIELD="$(echo "$CRUMB_JSON" | jq -r '.crumbRequestField // empty' 2>/dev/null || true)"
  CRUMB_VALUE="$(echo "$CRUMB_JSON" | jq -r '.crumb // empty' 2>/dev/null || true)"

  if [[ -n "$CRUMB_FIELD" && -n "$CRUMB_VALUE" ]]; then
    echo "✅ CSRF crumb acquired: ${CRUMB_FIELD}: ${CRUMB_VALUE:0:20}..."
  else
    echo "⚠️  CSRF endpoint returned 200 but couldn't parse crumb."
    echo "   Trying to proceed without CSRF token..."
  fi
elif [[ "$CRUMB_CODE" == "404" ]]; then
  echo "ℹ️  No CSRF protection detected (404 on crumbIssuer)."
else
  echo "⚠️  Could not fetch CSRF crumb (HTTP $CRUMB_CODE)."
  echo "   Trying to proceed without CSRF token..."
fi

rm -f "$CRUMB_RESP"
echo

# --- 4. Check permissions ---

echo "Checking plugin upload permissions..."
PERMS_RESP="$(mktemp)"
PERMS_CODE="$(curl -s -w '%{http_code}' -o "$PERMS_RESP" \
  -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
  "${JENKINS_URL}/pluginManager/" 2>&1 || echo "000")"

if [[ "$PERMS_CODE" == "200" ]]; then
  # Check if the response contains the upload form
  if grep -q "uploadPlugin" "$PERMS_RESP" 2>/dev/null; then
    echo "✅ User has access to Plugin Manager"
  else
    echo "⚠️  Warning: May not have upload permissions"
  fi
elif [[ "$PERMS_CODE" == "403" ]]; then
  echo "❌ Access denied to Plugin Manager!" >&2
  echo "   User '${JENKINS_USER}' needs one of these permissions:" >&2
  echo "   - Overall/Administer" >&2
  echo "   - Hudson.ADMINISTER" >&2
  rm -f "$PERMS_RESP"
  exit 1
else
  echo "⚠️  Could not verify Plugin Manager access (HTTP $PERMS_CODE)"
fi
rm -f "$PERMS_RESP"
echo

# --- 5. Check current installed version (if any) ---

echo "Checking currently installed plugins..."

PLUGINS_JSON="$(curl -s -u "${JENKINS_USER}:${JENKINS_TOKEN}" \
  "${JENKINS_URL}/pluginManager/api/json?depth=1")"

INSTALLED_VERSION="$(echo "$PLUGINS_JSON" \
  | jq -r --arg name "$PLUGIN_SHORT_NAME" \
    '.plugins[]? | select(.shortName == $name) | .version // empty')"

if [[ -n "$INSTALLED_VERSION" ]]; then
  echo "Currently installed:"
  echo "  ${PLUGIN_SHORT_NAME} : ${INSTALLED_VERSION}"
else
  echo "Plugin ${PLUGIN_SHORT_NAME} is NOT currently installed."
fi

echo

# (Optional) Basic version comparison (string-based, not perfect semver)
if [[ -n "$INSTALLED_VERSION" ]]; then
  if [[ "$INSTALLED_VERSION" == "$PLUGIN_VERSION" ]]; then
    echo "NOTE: Installed version is same as local version (${PLUGIN_VERSION})."
    echo "      Re-deploying anyway..."
    echo
  fi
fi

# --- 6. Upload the plugin ---

echo "Uploading plugin file: $PLUGIN_FILE"
echo "Target URL: ${JENKINS_URL}/pluginManager/uploadPlugin"
echo

UPLOAD_RESP_FILE="$(mktemp)"
UPLOAD_HEADERS_FILE="$(mktemp)"

# Build curl command with optional crumb header
CURL_OPTS=(
  -s -w '%{http_code}'
  -o "$UPLOAD_RESP_FILE"
  -D "$UPLOAD_HEADERS_FILE"
  -u "${JENKINS_USER}:${JENKINS_TOKEN}"
)

# Add CSRF crumb header if available
if [[ -n "$CRUMB_FIELD" && -n "$CRUMB_VALUE" ]]; then
  CURL_OPTS+=(-H "${CRUMB_FIELD}: ${CRUMB_VALUE}")
fi

CURL_OPTS+=(
  -F "file=@${PLUGIN_FILE}"
  "${JENKINS_URL}/pluginManager/uploadPlugin"
)

echo "Executing upload..."
HTTP_CODE="$(curl "${CURL_OPTS[@]}")"

echo "HTTP status: ${HTTP_CODE}"
echo
echo "Response headers:"
cat "$UPLOAD_HEADERS_FILE"
echo
echo "Response body:"
cat "$UPLOAD_RESP_FILE"
echo

if [[ "$HTTP_CODE" -ge 400 ]]; then
  echo "❌ Upload failed with HTTP ${HTTP_CODE}." >&2
  echo "Possible causes:" >&2
  echo "  - Jenkins permissions issue (ensure user has Overall/Administer or PluginManager/Upload)" >&2
  echo "  - CSRF protection issue" >&2
  echo "  - Plugin dependency conflicts" >&2
  echo "  - Jenkins version incompatibility" >&2
  echo "  - Check Jenkins logs for more details" >&2
  rm -f "$UPLOAD_RESP_FILE" "$UPLOAD_HEADERS_FILE"
  exit 1
fi

rm -f "$UPLOAD_RESP_FILE" "$UPLOAD_HEADERS_FILE"

echo "✅ Upload completed successfully!"

# --- 7. Optionally restart Jenkins so latest version is activated ---

echo
read -r -p "Trigger safe restart so the new plugin version takes effect? [y/N]: " ans
case "$ans" in
  [yY][eE][sS]|[yY])
    echo "Requesting safe restart..."
    
    RESTART_OPTS=(
      -sSf
      -u "${JENKINS_USER}:${JENKINS_TOKEN}"
    )
    
    # Add CSRF crumb header if available
    if [[ -n "$CRUMB_FIELD" && -n "$CRUMB_VALUE" ]]; then
      RESTART_OPTS+=(-H "${CRUMB_FIELD}: ${CRUMB_VALUE}")
    fi
    
    RESTART_OPTS+=(
      -X POST
      "${JENKINS_URL}/safeRestart"
    )
    
    if curl "${RESTART_OPTS[@]}"; then
      echo "✅ Safe restart triggered."
    else
      echo "⚠️  Restart request may have failed. Check Jenkins manually."
    fi
    ;;
  *)
    echo "Skipping restart. The plugin may require a Jenkins restart to fully activate."
    ;;
esac