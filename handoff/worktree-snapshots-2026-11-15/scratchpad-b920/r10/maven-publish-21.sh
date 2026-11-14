missing=""
[ "$USER_SET" = true ] || missing="$missing MAVEN_CENTRAL_TOKEN_USERNAME"
[ "$PASS_SET" = true ] || missing="$missing MAVEN_CENTRAL_TOKEN_PASSWORD"
[ "$KEY_SET" = true ] || missing="$missing MAVEN_GPG_PRIVATE_KEY"
[ "$PHRASE_SET" = true ] || missing="$missing MAVEN_GPG_PASSPHRASE"
if [ -n "$missing" ]; then echo "::error title=BLOCKED::environment package-release has no$missing"; exit 1; fi
