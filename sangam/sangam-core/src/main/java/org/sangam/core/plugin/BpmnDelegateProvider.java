// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.plugin;

import java.util.Map;

/**
 * Plugin SPI ({@link java.util.ServiceLoader}): BPMN delegates a drop-in plugin contributes. Each value is an
 * Operaton {@code JavaDelegate}, {@code ExecutionListener} or {@code TaskListener}; the server registers it as a
 * named bean, so a model refers to it with {@code operaton:delegateExpression="${name}"}. Names must be unique across
 * the platform; the plugin loader refuses a name that already exists. sangam-core does not import Operaton, so the
 * values are typed {@code Object} and checked by the loader.
 */
public interface BpmnDelegateProvider {

    /** Bean name to delegate instance. */
    Map<String, Object> delegates();
}
