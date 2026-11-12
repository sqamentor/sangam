// SPDX-License-Identifier: Apache-2.0
// semgrep --test cases for sangam-secret-in-log.yaml (not compiled; outside the Maven reactor)
class SecretInLogCases {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecretInLogCases.class);

    record Credentials(String user, String password) {
        String getPassword() {
            return password;
        }
    }

    void secretVariable(String userId, String accessToken) {
        // ruleid: sangam-secret-in-log
        log.info("login {} with {}", userId, accessToken);
    }

    void secretGetter(Credentials creds) {
        // ruleid: sangam-secret-in-log
        log.debug("connecting as {}", creds.getPassword());
    }

    void messageOnly(String userId) {
        // ok: sangam-secret-in-log
        log.info("token refreshed for {}", userId);
    }

    void notALogger(StringBuilder out, String apiKey) {
        // ok: sangam-secret-in-log
        out.append(apiKey);
    }
}
