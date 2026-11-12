// SPDX-License-Identifier: Apache-2.0
// semgrep --test cases for sangam-tls-verification-disabled.yaml (not compiled; outside the Maven reactor)
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.X509TrustManager;

class TlsCases {

    static class TrustEverything implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException();
        }

        // ruleid: sangam-tls-verification-disabled
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static class Delegating implements X509TrustManager {
        private final X509TrustManager delegate;

        Delegating(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        // ok: sangam-tls-verification-disabled
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    void anyHost(HttpsURLConnection conn) {
        // ruleid: sangam-tls-verification-disabled
        conn.setHostnameVerifier((host, session) -> true);
    }

    void anyHostEverywhere() {
        // ruleid: sangam-tls-verification-disabled
        HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
    }

    void jdkClientFlag() {
        // ruleid: sangam-tls-verification-disabled
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    void realVerifier(HttpsURLConnection conn, javax.net.ssl.HostnameVerifier verifier) {
        // ok: sangam-tls-verification-disabled
        conn.setHostnameVerifier(verifier);
    }

    void otherProperty() {
        // ok: sangam-tls-verification-disabled
        System.setProperty("jdk.httpclient.connectionPoolSize", "8");
    }
}
