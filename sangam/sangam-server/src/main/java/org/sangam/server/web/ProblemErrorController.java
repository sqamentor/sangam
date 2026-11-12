// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.ProblemHandler;
import org.sangam.rest.web.Problems;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * SL-71: the one generic problem answer for every error that no controller advice handled, on every Sangam controller
 * (present and future, also the module controllers without an advice such as {@code OpsController} and
 * {@code OpsUiController}) and on the container's error dispatch of {@code /engine-rest}. Spring Boot's
 * {@code BasicErrorController} answered those with its own JSON ({@code timestamp}, {@code error}, {@code path}), not
 * RFC 9457 {@code application/problem+json}; replacing the {@link ErrorController} bean makes Boot's error
 * auto-configuration back off ({@code @ConditionalOnMissingBean(ErrorController.class)}).
 *
 * <p>The answer is the one {@code /api} gives: the original exception (Boot's {@link ErrorAttributes#getError}, else
 * the servlet error attribute) goes through {@link ProblemHandler}'s own handler methods, so the codes and statuses are
 * the same as on {@code /api}; without an exception the dispatch status is answered with its default title. No
 * exception message of a 5xx is echoed (ProblemHandler's {@code INTERNAL_ERROR}). A direct request to {@code /error}
 * stays denied by the security chain (FWT-D11).
 */
@RestController
public class ProblemErrorController implements ErrorController {

    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);

    private final ErrorAttributes errors;
    private final ProblemHandler problems = new ProblemHandler();
    private final ExceptionHandlerMethodResolver handlers = new ExceptionHandlerMethodResolver(ProblemHandler.class);

    public ProblemErrorController(ErrorAttributes errors) {
        this.errors = errors;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest req) {
        Throwable e = errors == null ? null : errors.getError(new ServletWebRequest(req));
        if (e == null && req.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable t) {
            e = t;
        }
        int status = status(req);
        if (e != null) {
            ResponseEntity<Map<String, Object>> mapped = viaProblemHandler(e, req, status);
            if (mapped != null) {
                return mapped;
            }
        }
        ApiProblem p = new ApiProblem(status, code(status), Problems.defaultTitle(status));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(PROBLEM);
        return new ResponseEntity<>(Problems.body(p, (String) req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)), h,
                HttpStatus.valueOf(status));
    }

    /** The ProblemHandler method for the exception or, else, for the nearest cause (as Spring resolves advices). */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> viaProblemHandler(Throwable e, HttpServletRequest req, int status) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable x = e; x != null && seen.put(x, Boolean.TRUE) == null; x = x.getCause()) {
            if (!(x instanceof Exception ex)) {
                continue;
            }
            Method m = handlers.resolveMethodByExceptionType(ex.getClass());
            if (m != null && status < 500 && catchAll(m)) {
                return null; // a 4xx the container already decided (e.g. ResponseStatusException): keep its status
            }
            if (m != null) {
                try {
                    return (ResponseEntity<Map<String, Object>>) m.invoke(problems, ex, req);
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ignored) {
                    return null; // fall back to the status answer
                }
            }
        }
        return null;
    }

    /** ProblemHandler's last-resort handler ({@code @ExceptionHandler(Exception.class)}, always 500). */
    private static boolean catchAll(Method m) {
        org.springframework.web.bind.annotation.ExceptionHandler a =
                m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
        return a != null && java.util.Arrays.asList(a.value()).contains(Exception.class);
    }

    private static int status(HttpServletRequest req) {
        Object s = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int v = s instanceof Integer i ? i : 500;
        return HttpStatus.resolve(v) == null || v < 400 ? 500 : v;
    }

    /** Stable codes for a status without an exception (the ones {@code /api} uses where it has them). */
    static String code(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "ACTION_NOT_PERMITTED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "HTTP_" + status;
        };
    }
}
