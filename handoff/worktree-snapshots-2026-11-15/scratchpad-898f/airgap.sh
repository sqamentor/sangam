set -e
S="/c/Users/LOKEND~2/AppData/Local/Temp/claude/5cef910d4d286f64/898f1ff7-0dcc-4d95-b425-830a9a5ead44/scratchpad"
export PATH="$S/bin:$PATH"
D="$S/airgap-dry/deploy"
bash "$D/airgap/save-images.sh" --version 0.0.0-sg14 --no-pull --out "$S/airgap-out" 2>&1 | tail -8
X="$S/airgap-x"; rm -rf "$X"; mkdir -p "$X"; cd "$S/airgap-out"; sha256sum -c sangam-airgap-0.0.0-sg14.tar.sha256
tar -C "$X" -xf sangam-airgap-0.0.0-sg14.tar; bash "$X/sangam-airgap-0.0.0-sg14/verify-bundle.sh"
echo "tamper test:"; echo x >> "$X/sangam-airgap-0.0.0-sg14/BUNDLE-INFO.txt"; if bash "$X/sangam-airgap-0.0.0-sg14/verify-bundle.sh" >/dev/null 2>&1; then echo "TAMPER NOT DETECTED"; else echo "tamper detected (verify failed as expected)"; fi
cat "$X/sangam-airgap-0.0.0-sg14/images.lock"
