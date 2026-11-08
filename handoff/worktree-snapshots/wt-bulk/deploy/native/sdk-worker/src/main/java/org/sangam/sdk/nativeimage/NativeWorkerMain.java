// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.nativeimage;

/**
 * Entry point of the native-light worker image (SANGAM.md R7).
 *
 * <ul>
 *   <li>{@code sangam-sdk-worker --smoke}: offline compatibility check ({@link NativeSmoke}), exit 0/1;</li>
 *   <li>{@code sangam-sdk-worker --version}: prints the SDK build version;</li>
 *   <li>otherwise: runs the P1 bill-payment worker ({@code org.sangam.sdk.examples.BillPaymentWorker}), configured by
 *       {@code SANGAM_URL}, {@code SANGAM_TOKEN} or {@code SANGAM_OIDC_TOKEN_URL}/{@code SANGAM_CLIENT_ID}/
 *       {@code SANGAM_CLIENT_SECRET}, {@code SANGAM_TOPIC}, {@code SANGAM_WORKER_ID}, {@code TREASURY_URL}.</li>
 * </ul>
 * Own workers: copy this module, replace the delegate below with your handler's main class and regenerate the
 * metadata for your own payload types (deploy/SUPPORT-MATRIX.md "Native-light profile").
 */
public final class NativeWorkerMain {

    private NativeWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--smoke".equals(args[0])) {
            System.exit(NativeSmoke.run());
        }
        if (args.length > 0 && "--version".equals(args[0])) {
            Package p = org.sangam.sdk.SangamClient.class.getPackage();
            System.out.println("sangam-sdk-worker (native-light) " + (p.getImplementationVersion() == null
                    ? System.getProperty("sangam.sdk.version", "dev") : p.getImplementationVersion()));
            return;
        }
        org.sangam.sdk.examples.BillPaymentWorker.main(args);
    }
}
