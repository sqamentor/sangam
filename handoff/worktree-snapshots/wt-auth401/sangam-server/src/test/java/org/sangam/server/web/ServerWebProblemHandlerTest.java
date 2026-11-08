// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.ProblemHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * approve-401: an {@code ApiProblem} thrown by a controller of {@code org.sangam.server.web} (the onboarding maker's own
 * approval, 422) must be answered as that problem. {@link ProblemHandler} alone is scoped to {@code org.sangam.rest.web}
 * and does not apply here; the exception then went to the container's {@code /error} dispatch, which the security
 * chain answered 401 UNAUTHENTICATED.
 */
class ServerWebProblemHandlerTest {

    @RestController
    static class Approvals {
        @PostMapping("/runs/approve")
        Object approve() {
            throw new ApiProblem(422, "MAKER_IS_CHECKER", "the maker cannot approve the own run");
        }
    }

    @Test
    void problemOfAServerControllerIsAnsweredAsTheProblem() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Approvals())
                .setControllerAdvice(new ProblemHandler(), new ServerWebProblemHandler()).build();
        mvc.perform(post("/runs/approve"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("MAKER_IS_CHECKER"));
    }
}
