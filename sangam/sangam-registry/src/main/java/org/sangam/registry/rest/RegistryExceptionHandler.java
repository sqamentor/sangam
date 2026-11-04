// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.rest;

import org.sangam.registry.RegistryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Maps registry errors to RFC 9457 problem+json (ARCHITECTURE.md section 4). */
@RestControllerAdvice(basePackages = "org.sangam.registry.rest")
public class RegistryExceptionHandler {

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ProblemDetail> registry(RegistryException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.code().status()), e.getMessage());
        p.setType(URI.create("https://sangam.dev/problems/registry-" + e.code().name().toLowerCase().replace('_', '-')));
        p.setTitle("Registry: " + e.code().name());
        return ResponseEntity.status(e.code().status()).body(p);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setType(URI.create("https://sangam.dev/problems/registry-invalid"));
        p.setTitle("Registry: INVALID");
        return ResponseEntity.badRequest().body(p);
    }
}
