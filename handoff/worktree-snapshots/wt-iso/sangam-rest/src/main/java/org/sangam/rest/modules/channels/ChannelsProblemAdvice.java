// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.channels.conversation.ConversationService;
import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** problem+json for the channels controllers. A non-participant gets 403; refusals 422. */
@RestControllerAdvice(assignableTypes = {ChannelsController.class, ChannelHooksController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChannelsProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler(ConversationService.AccessDenied.class)
    public ResponseEntity<Map<String, Object>> denied(ConversationService.AccessDenied e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.forbidden("CONVERSATION_ACCESS_DENIED", "Not a participant"), req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> rejected(RuntimeException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("CHANNELS_REJECTED", e.getMessage()), req);
    }
}
