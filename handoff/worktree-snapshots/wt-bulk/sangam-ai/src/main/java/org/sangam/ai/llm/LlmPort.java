// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.llm;

/**
 * Provider-neutral LLM port. {@link #disabled()} is the default binding: it makes no outbound call and fails fast with
 * {@link Llm.DisabledException}, so an unconfigured installation stays air-gapped (ARCHITECTURE.md §1.9).
 */
public interface LlmPort {

    boolean enabled();

    /** Model identity recorded in the audit trail. */
    String modelId();

    Llm.Response complete(Llm.Request request);

    /** One vector per call. Providers without an embedding model throw {@link Llm.CallException}. */
    float[] embed(String tenantId, String text);

    static LlmPort disabled() {
        return new LlmPort() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String modelId() {
                return "disabled";
            }

            @Override
            public Llm.Response complete(Llm.Request request) {
                throw new Llm.DisabledException();
            }

            @Override
            public float[] embed(String tenantId, String text) {
                throw new Llm.DisabledException();
            }
        };
    }
}
