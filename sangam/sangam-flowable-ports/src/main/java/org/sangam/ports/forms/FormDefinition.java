// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Form definition (FC-39 forms API; EC-02 runtime; R47 contract). JSON model, versioned in {@code FORM_DEF}.
 * Flowable form JSON ({@code fields[]} with {@code id,type,name,required,params}) and form-js schemas (via
 * {@link FormJsConverter}) both map to this model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormDefinition(@JsonProperty("key") String key,
                             @JsonProperty("name") String name,
                             @JsonProperty("fields") List<Field> fields,
                             @JsonProperty("outcomes") List<Outcome> outcomes) {

    public FormDefinition {
        Objects.requireNonNull(key, "form key");
        fields = fields == null ? List.of() : List.copyOf(fields);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /**
     * One field. {@code type}: text, textarea, email, phone, integer, decimal, boolean, date, datetime, select, radio,
     * multiselect, json, document (a content-item id, FC-40), expression (read-only, display only).
     * {@code scope}: {@code process} (default) or {@code local} (task-local variable, Flowable variable-scoped forms).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(@JsonProperty("id") String id,
                        @JsonProperty("type") String type,
                        @JsonProperty("label") String label,
                        @JsonProperty("variable") String variable,
                        @JsonProperty("scope") String scope,
                        @JsonProperty("required") Boolean required,
                        @JsonProperty("readOnly") Boolean readOnly,
                        @JsonProperty("minLength") Integer minLength,
                        @JsonProperty("maxLength") Integer maxLength,
                        @JsonProperty("min") Double min,
                        @JsonProperty("max") Double max,
                        @JsonProperty("pattern") String pattern,
                        @JsonProperty("options") List<Option> options,
                        @JsonProperty("defaultValue") Object defaultValue,
                        @JsonProperty("visibleIf") Condition visibleIf,
                        @JsonProperty("requiredIf") Condition requiredIf) {

        public String variableName() {
            return variable == null || variable.isBlank() ? id : variable;
        }

        public boolean local() {
            return "local".equalsIgnoreCase(scope);
        }

        public boolean isRequired() {
            return Boolean.TRUE.equals(required);
        }

        public boolean isReadOnly() {
            return Boolean.TRUE.equals(readOnly) || "expression".equals(type);
        }

        public String typeOrText() {
            return type == null ? "text" : type;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(@JsonProperty("value") String value, @JsonProperty("label") String label) {
    }

    /** {@code field} equals one of {@code in} (string comparison); a declarative rule, no code execution. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(@JsonProperty("field") String field, @JsonProperty("in") List<String> in) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Outcome(@JsonProperty("id") String id, @JsonProperty("name") String name) {
    }

    public Field field(String id) {
        return fields.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }
}
