case "$GROUP" in
  io.github.sqamentor) echo "Maven Central namespace: $GROUP" ;;
  *) echo "::error title=BLOCKED::repository variable SANGAM_MAVEN_GROUP_ID must be io.github.sqamentor (decision 210); it is '${GROUP}'"; exit 1 ;;
esac
