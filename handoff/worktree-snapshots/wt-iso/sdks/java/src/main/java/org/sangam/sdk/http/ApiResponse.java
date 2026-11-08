// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import java.net.http.HttpHeaders;

/** Decoded response: status, headers and body. */
public record ApiResponse<T>(int status, HttpHeaders headers, T body) {

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }
}
