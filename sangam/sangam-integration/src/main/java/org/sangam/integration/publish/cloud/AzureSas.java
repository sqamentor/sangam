// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.cloud;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;

/**
 * Azure Shared Access Signature token for Service Bus (and Event Hubs) REST calls (M-064). This is the product's one
 * SAS signer: {@code sangam-channels PolledChannels.ServiceBusChannel} computes the same token inline and is asked to
 * call this (W3-C). Token: {@code SharedAccessSignature sr=<url-encoded resource>&sig=<url-encoded base64
 * HMAC-SHA256(key, url-encoded resource + "\n" + expiry)>&se=<expiry epoch seconds>&skn=<key name>}.
 */
public final class AzureSas {

    private AzureSas() {
    }

    /**
     * @param resourceUri the entity URI the token is valid for (e.g. {@code https://ns.servicebus.windows.net/queue})
     * @param keyName     the shared access policy name
     * @param key         the policy key (the secret value, never logged)
     * @param expiry      when the token stops being valid
     */
    public static String token(String resourceUri, String keyName, String key, Instant expiry) {
        String resource = URLEncoder.encode(resourceUri, StandardCharsets.UTF_8);
        long se = expiry.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getEncoder().encodeToString(mac.doFinal((resource + "\n" + se).getBytes(StandardCharsets.UTF_8)));
            return "SharedAccessSignature sr=" + resource + "&sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8) + "&se=" + se
                    + "&skn=" + keyName;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
