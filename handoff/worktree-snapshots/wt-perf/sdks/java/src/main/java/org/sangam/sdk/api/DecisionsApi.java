// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.DecisionResult;
import org.sangam.sdk.model.EvaluateDecisionRequest;

import java.util.Map;

/** DMN 1.6 decisions {@code POST /decisions/{key}/evaluate}. */
public final class DecisionsApi extends ApiSupport {

    public DecisionsApi(HttpTransport http) {
        super(http);
    }

    public DecisionResult evaluate(String decisionKey, EvaluateDecisionRequest request) {
        return call(ApiRequest.post("/decisions/" + seg(decisionKey) + "/evaluate").body(request), DecisionResult.class);
    }

    public DecisionResult evaluate(String decisionKey, Map<String, Object> variables) {
        return evaluate(decisionKey, new EvaluateDecisionRequest(variables, null, null, null));
    }
}
