missing=""
[ "$NPM" = true ] || missing="$missing NPM_TOKEN"
[ "$PYPI" = true ] || missing="$missing PYPI_TOKEN"
[ "$NUGET" = true ] || missing="$missing NUGET_API_KEY"
if [ -n "$missing" ]; then echo "::error::BLOCKED: environment package-release has no$missing"; exit 1; fi
