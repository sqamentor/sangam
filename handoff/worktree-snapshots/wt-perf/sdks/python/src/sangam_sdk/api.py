# SPDX-License-Identifier: Apache-2.0
"""Typed API groups for ``/api/v1``, reconciled with ``sangam-api.yaml`` 0.1.0.

Every mutation takes an optional ``idempotency_key`` (None = one is generated per logical call and reused across
transport retries, R41; the government profile requires it on start, correlate, task decisions, cancel, delete and
deploy, which the SDK always satisfies). VariableMap arguments are encoded with R33 TypedValues
(:func:`sangam_sdk.typed.encode_variables`). Result objects carry ``idempotent_replayed``.
"""
from __future__ import annotations

import uuid
from typing import Any, Callable, Mapping, Optional, Sequence, TypeVar

from .http import ApiRequest, ApiResponse, HttpTransport, seg
from .models import (AuditEntry, AuditVerifyResult, CaseInstance, CloudEvent, CompleteTaskResult,
                     DecisionEvaluateResult, Deployment, ExternalWork, LockState, MessageResult, OutcomeUnknownResult,
                     Page, ProcessInstance, PublishResult, ReconciliationItem, Task, TerminationResult, compact)
from .typed import encode_variables

T = TypeVar("T")
Vars = Optional[Mapping[str, Any]]


def _iso(v: Any) -> Any:
    return v.isoformat().replace("+00:00", "Z") if hasattr(v, "isoformat") else v


class _Api:
    def __init__(self, http: HttpTransport) -> None:
        self._http = http

    def _resp(self, req: ApiRequest) -> ApiResponse:
        return self._http.execute(req)

    def _call(self, req: ApiRequest) -> Any:
        return self._http.execute(req).body

    def _obj(self, req: ApiRequest, model: Any) -> Any:
        r = self._http.execute(req)
        obj = model.from_dict(r.body)
        if obj is not None:
            obj.idempotent_replayed = r.replayed
        return obj

    def _page(self, req: ApiRequest, conv: Callable[[Any], T]) -> Page[T]:
        r = self._http.execute(req)
        body = r.body
        arr = body if isinstance(body, list) else (body.get("items") if isinstance(body, dict) else None)
        items = [conv(i) for i in (arr or [])]
        total: int | None = None
        h = r.header("X-Total-Count")
        if h is not None:
            try:
                total = int(h.strip())
            except ValueError:
                total = None
        if total is None and isinstance(body, dict) and body.get("totalCount") is not None:
            total = int(body["totalCount"])
        capped = (r.header("X-Total-Count-Capped") or "").lower() == "true" or (
            isinstance(body, dict) and bool(body.get("totalCountCapped")))
        q = req.query or {}
        page = int(body.get("page", 0)) if isinstance(body, dict) else int(q.get("page") or 0)
        size = int(body.get("size", len(items))) if isinstance(body, dict) else int(q.get("size") or len(items))
        return Page(items, page, size, total, capped)


def _ident(x: Any) -> Any:
    return x


class WorkApi(_Api):
    """Worker API ``/work`` (VI.3, fenced by ``(workId, lockVersion, attemptId)``; 409 STALE_LEASE raises
    :class:`~sangam_sdk.errors.StaleLeaseError`). Most code uses :class:`~sangam_sdk.worker.Worker`."""

    def fetch_and_lock(self, worker_id: str, topics: Sequence[Mapping[str, Any]], max_tasks: int,
                       async_response_timeout_ms: int, use_priority: bool | None = None) -> list[ExternalWork]:
        """Long poll. ``topics`` items are TopicRequests: ``name``, ``lockDurationMs``, ``variables``,
        ``localVariables``, ``processDefinitionKey``, ``businessKey``. Not retried by the transport."""
        body = compact({"workerId": worker_id, "topics": [compact(t) for t in topics], "maxTasks": max_tasks,
                        "asyncResponseTimeoutMs": async_response_timeout_ms, "usePriority": use_priority})
        res = self._call(ApiRequest("POST", "/work/fetch-and-lock", body=body,
                                    timeout=async_response_timeout_ms / 1000.0 + 15.0, retry=False))
        return [ExternalWork.from_dict(w) for w in (res or [])]

    def _post(self, work_id: str, action: str, body: Mapping[str, Any], key: str | None) -> ApiResponse:
        return self._resp(ApiRequest("POST", f"/work/{seg(work_id)}/{action}", body=compact(body),
                                     idempotency_key=key))

    def extend_lock(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None,
                    new_duration_ms: int, idempotency_key: str | None = None) -> LockState | None:
        return LockState.from_dict(self._post(work_id, "extend-lock", {
            "workerId": worker_id, "lockVersion": lock_version, "attemptId": attempt_id,
            "newDurationMs": new_duration_ms}, idempotency_key).body)

    def complete(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None,
                 variables: Vars = None, local_variables: Vars = None, idempotency_key: str | None = None,
                 outcome_reference: str | None = None, outcome_details: dict[str, Any] | None = None) -> None:
        """``outcome_reference`` (downstream reference) is recorded as the ledger outcome (SL-16)."""
        body = {"workerId": worker_id, "lockVersion": lock_version, "attemptId": attempt_id,
                "variables": encode_variables(variables), "localVariables": encode_variables(local_variables)}
        if outcome_reference is not None or outcome_details is not None:
            body["outcome"] = {"reference": outcome_reference, "details": outcome_details}
        self._post(work_id, "complete", body, idempotency_key)

    def fail(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None, error_message: str,
             error_details: str | None, retries: int, retry_timeout_ms: int, idempotency_key: str | None = None) -> None:
        self._post(work_id, "fail", {"workerId": worker_id, "lockVersion": lock_version, "attemptId": attempt_id,
                                     "errorMessage": error_message, "errorDetails": error_details,
                                     "retries": retries, "retryTimeoutMs": retry_timeout_ms}, idempotency_key)

    def bpmn_error(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None, error_code: str,
                   error_message: str | None = None, variables: Vars = None, idempotency_key: str | None = None) -> None:
        self._post(work_id, "bpmn-error", {"workerId": worker_id, "lockVersion": lock_version, "attemptId": attempt_id,
                                           "errorCode": error_code, "errorMessage": error_message,
                                           "variables": encode_variables(variables)}, idempotency_key)

    def unlock(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None,
               idempotency_key: str | None = None) -> None:
        self._post(work_id, "unlock", {"workerId": worker_id, "lockVersion": lock_version, "attemptId": attempt_id},
                   idempotency_key)

    def outcome_unknown(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None,
                        detail: str | None = None, evidence: Vars = None,
                        idempotency_key: str | None = None) -> OutcomeUnknownResult | None:
        """VI.3.2 rule 4 (202): opens an UNKNOWN_OUTCOME reconciliation item; never auto-repeated."""
        r = self._post(work_id, "outcome-unknown", {"workerId": worker_id, "lockVersion": lock_version,
                                                    "attemptId": attempt_id, "detail": detail,
                                                    "evidence": encode_variables(evidence)}, idempotency_key)
        res = OutcomeUnknownResult.from_dict(r.body)
        if res is not None:
            res.idempotent_replayed = r.replayed
        return res

    def cancel_ack(self, work_id: str, worker_id: str, lock_version: int, attempt_id: str | None,
                   idempotency_key: str | None = None) -> None:
        """VI.3.1 / VI.5: CANCEL_REQUESTED --worker ack--> CANCELLED."""
        self._post(work_id, "cancel-ack", {"workerId": worker_id, "lockVersion": lock_version,
                                           "attemptId": attempt_id}, idempotency_key)


class TasksApi(_Api):
    """User tasks ``/tasks`` (always filtered by the caller's VI.4 policy on the server)."""

    def list(self, *, assignee: str | None = None, mine: bool | None = None, unassigned: bool | None = None,
             candidate_group: str | None = None, seat_id: str | None = None, process_instance_id: str | None = None,
             case_instance_id: str | None = None, business_key: str | None = None,
             process_definition_key: str | None = None, task_definition_key: str | None = None,
             state: str | None = None, sort_by: str | None = None, sort_order: str | None = None,
             page: int | None = None, size: int | None = None, **extra: Any) -> Page[Task]:
        q = {"assignee": assignee, "mine": mine, "unassigned": unassigned, "candidateGroup": candidate_group,
             "seatId": seat_id, "processInstanceId": process_instance_id, "caseInstanceId": case_instance_id,
             "businessKey": business_key, "processDefinitionKey": process_definition_key,
             "taskDefinitionKey": task_definition_key, "state": state, "sortBy": sort_by, "sortOrder": sort_order,
             "page": page, "size": size, **extra}
        return self._page(ApiRequest("GET", "/tasks", query=q), Task.from_dict)

    def get(self, task_id: str) -> Task:
        return Task.from_dict(self._call(ApiRequest("GET", f"/tasks/{seg(task_id)}")))

    def variables(self, task_id: str) -> dict[str, Any]:
        """``GET /tasks/{id}/variables`` (VariableMap, TypedValues not unwrapped)."""
        return self._call(ApiRequest("GET", f"/tasks/{seg(task_id)}/variables")) or {}

    def claim(self, task_id: str, idempotency_key: str | None = None) -> None:
        """No body, 204. The seat comes from ``X-Sangam-Seat``."""
        self._call(ApiRequest("POST", f"/tasks/{seg(task_id)}/claim", idempotency_key=idempotency_key))

    def unclaim(self, task_id: str, idempotency_key: str | None = None) -> None:
        self._call(ApiRequest("POST", f"/tasks/{seg(task_id)}/unclaim", idempotency_key=idempotency_key))

    def complete(self, task_id: str, payload_hash: str, *, variables: Vars = None, decision: str | None = None,
                 remarks: str | None = None, case_version: int | None = None, form_version: str | None = None,
                 idempotency_key: str | None = None) -> CompleteTaskResult:
        """``payload_hash`` (required) must equal the hash the user approved (VI.4 rule 5)."""
        body = compact({"payloadHash": payload_hash, "caseVersion": case_version, "decision": decision,
                        "remarks": remarks, "formVersion": form_version, "variables": encode_variables(variables)})
        return self._obj(ApiRequest("POST", f"/tasks/{seg(task_id)}/complete", body=body,
                                    idempotency_key=idempotency_key), CompleteTaskResult)

    def return_task(self, task_id: str, reason: str, *, reason_code: str | None = None,
                    target_activity_id: str | None = None, variables: Vars = None,
                    idempotency_key: str | None = None) -> None:
        body = compact({"reason": reason, "reasonCode": reason_code, "targetActivityId": target_activity_id,
                        "variables": encode_variables(variables)})
        self._call(ApiRequest("POST", f"/tasks/{seg(task_id)}/return", body=body, idempotency_key=idempotency_key))

    def delegate(self, task_id: str, reason: str, *, to_user_id: str | None = None, to_seat_id: str | None = None,
                 idempotency_key: str | None = None) -> None:
        body = compact({"toUserId": to_user_id, "toSeatId": to_seat_id, "reason": reason})
        self._call(ApiRequest("POST", f"/tasks/{seg(task_id)}/delegate", body=body, idempotency_key=idempotency_key))


class ProcessesApi(_Api):
    """Process and case instances, messages, signals. The tenant always comes from the token."""

    def start(self, key: str, business_key: str | None = None, variables: Vars = None, *,
              version: int | None = None, package_hash: str | None = None, form_version: str | None = None,
              start_before_activity_ids: Sequence[str] | None = None,
              idempotency_key: str | None = None) -> ProcessInstance:
        """``POST /process-instances`` (201). Pass a business-derived key to make "start once" safe."""
        body = compact({"key": key, "businessKey": business_key, "variables": encode_variables(variables),
                        "version": version, "packageHash": package_hash, "formVersion": form_version,
                        "startBeforeActivityIds": list(start_before_activity_ids) if start_before_activity_ids else None})
        return self._obj(ApiRequest("POST", "/process-instances", body=body, idempotency_key=idempotency_key),
                         ProcessInstance)

    def list(self, *, key: str | None = None, business_key: str | None = None, state: str | None = None,
             started_after: Any = None, started_before: Any = None, with_incidents: bool | None = None,
             super_process_instance_id: str | None = None, page: int | None = None, size: int | None = None,
             **extra: Any) -> Page[ProcessInstance]:
        q = {"key": key, "businessKey": business_key, "state": state, "startedAfter": _iso(started_after),
             "startedBefore": _iso(started_before), "withIncidents": with_incidents,
             "superProcessInstanceId": super_process_instance_id, "page": page, "size": size, **extra}
        return self._page(ApiRequest("GET", "/process-instances", query=q), ProcessInstance.from_dict)

    def get(self, process_instance_id: str) -> ProcessInstance:
        return ProcessInstance.from_dict(self._call(ApiRequest("GET", f"/process-instances/{seg(process_instance_id)}")))

    def variables(self, process_instance_id: str) -> dict[str, Any]:
        return self._call(ApiRequest("GET", f"/process-instances/{seg(process_instance_id)}/variables")) or {}

    def cancel(self, process_instance_id: str, reason: str, reason_code: str | None = None,
               idempotency_key: str | None = None) -> TerminationResult:
        """Business cancel (VI.5): needs CANCEL_INSTANCE and a reason."""
        return self._obj(ApiRequest("POST", f"/process-instances/{seg(process_instance_id)}/cancel",
                                    body=compact({"reason": reason, "reasonCode": reason_code}),
                                    idempotency_key=idempotency_key), TerminationResult)

    def correlate_message(self, message_name: str, *, business_key: str | None = None,
                          process_instance_id: str | None = None, correlation_keys: Vars = None,
                          variables: Vars = None, all: bool | None = None,
                          idempotency_key: str | None = None) -> MessageResult:
        body = compact({"messageName": message_name, "businessKey": business_key,
                        "processInstanceId": process_instance_id,
                        "correlationKeys": encode_variables(correlation_keys),
                        "variables": encode_variables(variables), "all": all})
        return self._obj(ApiRequest("POST", "/messages", body=body, idempotency_key=idempotency_key), MessageResult)

    def signal(self, name: str, variables: Vars = None, execution_id: str | None = None,
               idempotency_key: str | None = None) -> None:
        body = compact({"name": name, "executionId": execution_id, "variables": encode_variables(variables)})
        self._call(ApiRequest("POST", "/signals", body=body, idempotency_key=idempotency_key))

    def start_case(self, key: str, business_key: str | None = None, variables: Vars = None, *,
                   idempotency_key: str | None = None) -> CaseInstance:
        """``POST /case-instances`` (CMMN 1.1)."""
        body = compact({"key": key, "businessKey": business_key, "variables": encode_variables(variables)})
        return self._obj(ApiRequest("POST", "/case-instances", body=body, idempotency_key=idempotency_key),
                         CaseInstance)


class DecisionsApi(_Api):
    """DMN 1.6 ``POST /decisions/{key}/evaluate``."""

    def evaluate(self, decision_key: str, variables: Vars = None, *, version: int | None = None,
                 purpose: str | None = None, business_key: str | None = None) -> DecisionEvaluateResult:
        """``purpose``: ROUTING | AUTHORITY | OTHER."""
        body = compact({"variables": encode_variables(variables), "version": version, "purpose": purpose,
                        "businessKey": business_key})
        return DecisionEvaluateResult.from_dict(self._call(ApiRequest(
            "POST", f"/decisions/{seg(decision_key)}/evaluate", body=body)))


class AuditApi(_Api):
    """Audit timeline and hash-chain verification (R42, AC-01)."""

    def timeline(self, business_key: str, include_history: bool | None = None, page: int | None = None,
                 size: int | None = None) -> Page[AuditEntry]:
        q = {"includeHistory": include_history, "page": page, "size": size}
        return self._page(ApiRequest("GET", f"/audit/{seg(business_key)}/timeline", query=q), AuditEntry.from_dict)

    def verify(self, chain_id: str | None = None, business_key: str | None = None) -> AuditVerifyResult:
        return AuditVerifyResult.from_dict(self._call(ApiRequest(
            "GET", "/audit/verify", query={"chainId": chain_id, "businessKey": business_key})))


class ResourceApi(_Api):
    """Generic CRUD over one admin/ops collection; bodies are JSON objects (dicts)."""

    def __init__(self, http: HttpTransport, path: str) -> None:
        super().__init__(http)
        self.path = path

    def list(self, filters: Mapping[str, Any] | None = None, page: int | None = None,
             size: int | None = None) -> Page[dict[str, Any]]:
        q = dict(filters or {})
        q.update({"page": page, "size": size})
        return self._page(ApiRequest("GET", self.path, query=q), _ident)

    def get(self, id: str) -> dict[str, Any]:
        return self._call(ApiRequest("GET", f"{self.path}/{seg(id)}"))

    def create(self, body: Mapping[str, Any], idempotency_key: str | None = None) -> dict[str, Any]:
        return self._call(ApiRequest("POST", self.path, body=dict(body), idempotency_key=idempotency_key))

    def update(self, id: str, body: Mapping[str, Any], idempotency_key: str | None = None,
               if_match: str | None = None) -> dict[str, Any]:
        h = {"If-Match": if_match} if if_match else {}
        return self._call(ApiRequest("PUT", f"{self.path}/{seg(id)}", body=dict(body),
                                     idempotency_key=idempotency_key, headers=h))

    def delete(self, id: str, idempotency_key: str | None = None) -> None:
        self._call(ApiRequest("DELETE", f"{self.path}/{seg(id)}", idempotency_key=idempotency_key))

    def get_sub(self, sub: str) -> Any:
        """``GET <path>/<sub>``, e.g. ``ops.outbox.get_sub("stats")``."""
        return self._call(ApiRequest("GET", f"{self.path}/{seg(sub)}"))

    def action(self, id: str, action: str, body: Mapping[str, Any] | None = None,
               idempotency_key: str | None = None) -> Any:
        """``POST <path>/<id>/<action>``, e.g. ``admin.assignments.action(id, "end", {...})``."""
        return self._call(ApiRequest("POST", f"{self.path}/{seg(id)}/{seg(action)}",
                                     body=dict(body) if body is not None else None, idempotency_key=idempotency_key))


class AdminApi:
    """Admin basics ``/admin/**`` (IX.3–IX.5, VI.4)."""

    def __init__(self, http: HttpTransport) -> None:
        self._http = http
        self.org_units = ResourceApi(http, "/admin/org-units")
        self.seats = ResourceApi(http, "/admin/seats")
        self.assignments = ResourceApi(http, "/admin/assignments")
        self.delegations = ResourceApi(http, "/admin/delegations")
        self.policies = ResourceApi(http, "/admin/policies")
        self.tenants = ResourceApi(http, "/admin/tenants")

    def resource(self, name: str) -> ResourceApi:
        return ResourceApi(self._http, "/admin/" + seg(name))

    def end_assignment(self, assignment_id: str, valid_to: Any, reason: str,
                       idempotency_key: str | None = None) -> Any:
        return self.assignments.action(assignment_id, "end", {"validTo": _iso(valid_to), "reason": reason},
                                       idempotency_key)

    def revoke_delegation(self, delegation_id: str, reason: str, idempotency_key: str | None = None) -> Any:
        return self.delegations.action(delegation_id, "revoke", {"reason": reason}, idempotency_key)


class OpsApi(_Api):
    """Incidents, reconciliation workbench (VI.3 UNKNOWN_OUTCOME), outbox, jobs."""

    def __init__(self, http: HttpTransport) -> None:
        super().__init__(http)
        self.incidents = ResourceApi(http, "/ops/incidents")
        self.outbox = ResourceApi(http, "/ops/outbox")
        self.jobs = ResourceApi(http, "/ops/jobs")

    def reconciliation(self, state: str | None = None, kind: str | None = None, page: int | None = None,
                       size: int | None = None) -> Page[ReconciliationItem]:
        """``state``: OPEN | RESOLVED; ``kind``: UNKNOWN_OUTCOME | LATE_RESULT_AFTER_DELETE."""
        q = {"state": state, "kind": kind, "page": page, "size": size}
        return self._page(ApiRequest("GET", "/ops/reconciliation", query=q), ReconciliationItem.from_dict)

    def reconciliation_item(self, id: str) -> ReconciliationItem:
        return ReconciliationItem.from_dict(self._call(ApiRequest("GET", f"/ops/reconciliation/{seg(id)}")))

    def resolve(self, id: str, action: str, evidence: str, result: Vars = None, reason: str | None = None,
                idempotency_key: str | None = None) -> ReconciliationItem:
        """``action``: CONFIRM_SUCCESS (with ``result``), RETRY_SAME_OPERATION or MARK_FAILED; ``evidence`` required."""
        body = compact({"action": action, "evidence": evidence, "result": encode_variables(result), "reason": reason})
        return self._obj(ApiRequest("POST", f"/ops/reconciliation/{seg(id)}/resolve", body=body,
                                    idempotency_key=idempotency_key), ReconciliationItem)

    def retry_incident(self, incident_id: str, reason: str, retries: int | None = None,
                       annotation: str | None = None, idempotency_key: str | None = None) -> Any:
        return self.incidents.action(incident_id, "retry",
                                     compact({"reason": reason, "retries": retries, "annotation": annotation}),
                                     idempotency_key)

    def set_job_retries(self, job_id: str, retries: int, reason: str, idempotency_key: str | None = None) -> Any:
        return self.jobs.action(job_id, "retries", {"retries": retries, "reason": reason}, idempotency_key)

    def requeue_outbox(self, outbox_id: str, reason: str, destination: str | None = None,
                       idempotency_key: str | None = None) -> Any:
        return self.outbox.action(outbox_id, "requeue", compact({"reason": reason, "destination": destination}),
                                  idempotency_key)

    def outbox_stats(self) -> Any:
        return self.outbox.get_sub("stats")


class RepositoryApi(_Api):
    """Deployments, event registry and forms."""

    def deploy(self, name: str, resources: Mapping[str, bytes], *, source: str | None = None,
               duplicate_filtering: bool | None = None, idempotency_key: str | None = None) -> Deployment:
        """``POST /deployments`` (201) as multipart/form-data: ``name``, ``source``, ``duplicateFiltering`` and one
        ``files`` part per resource (filename = resource name)."""
        boundary = "----sangam" + uuid.uuid4().hex
        parts: list[bytes] = []

        def text(n: str, v: str) -> None:
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{n}"\r\n\r\n{v}\r\n'.encode("utf-8"))

        text("name", name)
        if source is not None:
            text("source", source)
        if duplicate_filtering is not None:
            text("duplicateFiltering", "true" if duplicate_filtering else "false")
        for fn, data in resources.items():
            f = fn.replace('"', "")
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{f}"\r\n'
                         f"Content-Type: application/octet-stream\r\n\r\n".encode("utf-8"))
            parts.append(data)
            parts.append(b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode("utf-8"))
        return self._obj(ApiRequest("POST", "/deployments", raw_body=b"".join(parts),
                                    raw_content_type=f"multipart/form-data; boundary={boundary}",
                                    idempotency_key=idempotency_key), Deployment)

    def deployments(self, page: int | None = None, size: int | None = None) -> Page[Deployment]:
        return self._page(ApiRequest("GET", "/deployments", query={"page": page, "size": size}), Deployment.from_dict)

    def event_channels(self) -> list[dict[str, Any]]:
        return self._page(ApiRequest("GET", "/events/channels"), _ident).items

    def event_definitions(self) -> list[dict[str, Any]]:
        return self._page(ApiRequest("GET", "/events/definitions"), _ident).items

    def publish_event(self, channel: str, event: CloudEvent | Mapping[str, Any],
                      idempotency_key: str | None = None) -> PublishResult:
        """``POST /events/{channel}`` (202) with a CloudEvents 1.0 envelope (R16)."""
        body = event.to_dict() if isinstance(event, CloudEvent) else dict(event)
        return self._obj(ApiRequest("POST", f"/events/{seg(channel)}", body=body,
                                    idempotency_key=idempotency_key or body.get("id")), PublishResult)

    def publish(self, channel: str, source: str, type: str, data: Any = None, *, subject: str | None = None,
                id: str | None = None) -> PublishResult:
        """Helper: wraps ``data`` in a :class:`CloudEvent` and publishes it."""
        ev = CloudEvent(source=source, type=type, data=data, subject=subject)
        if id:
            ev.id = id
        return self.publish_event(channel, ev)

    def form(self, form_key: str) -> dict[str, Any]:
        return self._call(ApiRequest("GET", f"/forms/{seg(form_key)}"))
