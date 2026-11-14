set -e
apk add -q bash coreutils tar gzip >/dev/null
D=/w/deploy
bash $D/airgap/save-images.sh --version 0.0.0-sg14 --no-pull --out /w/out 2>&1 | tail -4
cd /w/out && sha256sum -c sangam-airgap-0.0.0-sg14.tar.sha256
rm -rf /w/x && mkdir /w/x && tar -C /w/x -xf sangam-airgap-0.0.0-sg14.tar
bash /w/x/sangam-airgap-0.0.0-sg14/verify-bundle.sh
echo x >> /w/x/sangam-airgap-0.0.0-sg14/BUNDLE-INFO.txt
if bash /w/x/sangam-airgap-0.0.0-sg14/verify-bundle.sh >/dev/null 2>&1; then echo "TAMPER NOT DETECTED"; else echo "tamper detected: verify failed as expected"; fi
cat /w/x/sangam-airgap-0.0.0-sg14/images.lock
ls -la /w/x/sangam-airgap-0.0.0-sg14 /w/x/sangam-airgap-0.0.0-sg14/images
