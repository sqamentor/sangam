type sleep; t0=$SECONDS; sleep 5; echo "sleep rc=$? elapsed=$((SECONDS-t0))"; ( sleep 1; echo sub ) & sleep 4; echo "rc2=$? elapsed=$((SECONDS-t0))"
