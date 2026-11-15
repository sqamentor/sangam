// SPDX-License-Identifier: Apache-2.0
//! Work API wire types, field for field from `sangam-rest/api/sangam-api.yaml` (components.schemas). Optional members
//! are `Option`s and are left out when `None`, so "absent" and "0" stay different.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// Variables by name: plain JSON values or R33 TypedValue objects (`{"type":"Decimal","value":"1234.50"}`).
pub type VariableMap = Map<String, Value>;

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkOperation {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempts: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capability_class: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub may_dispatch: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stored_outcome: Option<Map<String, Value>>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExternalWork {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub topic: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tenant_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub process_instance_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub process_definition_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub activity_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub activity_instance_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variables: Option<VariableMap>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_version: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_expires_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retries: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub priority: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation: Option<WorkOperation>,
    /// M-062: W3C trace context the engine stored for the task; None when none was stored.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trace_context: Option<TraceContext>,
}

/// W3C Trace Context of the request that created the work (R13); `traceparent` is required.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TraceContext {
    pub traceparent: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tracestate: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TopicRequest {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_duration_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variables: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_variables: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub process_definition_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_key: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchAndLockRequest {
    pub worker_id: String,
    pub topics: Vec<TopicRequest>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_tasks: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub async_response_timeout_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub use_priority: Option<bool>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LockState {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_version: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lock_expires_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cancel_requested: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExtendLockRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    pub new_duration_ms: i64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkOutcome {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reference: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub details: Option<Map<String, Value>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkCompleteRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variables: Option<VariableMap>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_variables: Option<VariableMap>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<WorkOutcome>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkFailRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_details: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retries: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_timeout_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkBpmnErrorRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    pub error_code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub variables: Option<VariableMap>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutcomeUnknownRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub evidence: Option<VariableMap>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelAckRequest {
    pub worker_id: String,
    pub lock_version: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_id: Option<String>,
}

/// RFC 9457 problem details; `code` is the stable machine code (for example STALE_LEASE). Members the schema does not
/// name (workId, reason, ...) are kept in `extensions`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Problem {
    #[serde(default, rename = "type", skip_serializing_if = "Option::is_none")]
    pub type_: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instance: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<String>,
    /// Present with POWER_LIMIT_EXCEEDED: the decision that refused the action (decision 142), kept as the server
    /// sent it (schema PowerLimitDecision).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub power_limit: Option<Value>,
    #[serde(flatten)]
    pub extensions: Map<String, Value>,
}
