// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.pipeline;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.file.FileTransport;
import org.sangam.integration.relay.OutboxWriter;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;

/**
 * Named external resources a pipeline definition may refer to. Definitions hold names only, never URLs of databases or
 * credentials, so a definition cannot reach anything the site did not configure.
 *
 * @param dataSources extract/load databases by name
 * @param files       {@code file} and {@code sftp} transports by name
 * @param http        egress-controlled transport (R27)
 * @param outbox      write side for outbox loads and dataset events
 * @param decisions   DMN port; null when no DMN steps are used
 * @param stageRoot   staging directory; must be shared storage for a restart on another node
 */
public record PipelineResources(Map<String, DataSource> dataSources, Map<String, FileTransport> files, HttpTransport http,
                                SecretResolver secrets, OutboxWriter outbox, DecisionPort decisions, Path stageRoot) {

    /** Evaluates a deployed DMN decision; returns the first result row. */
    @FunctionalInterface
    public interface DecisionPort {
        Map<String, Object> evaluate(String tenantId, String decisionKey, Map<String, Object> variables);
    }

    DataSource dataSource(String name) {
        DataSource d = dataSources.get(name);
        if (d == null) {
            throw new IllegalArgumentException("no data source named " + name);
        }
        return d;
    }

    FileTransport file(String name) {
        FileTransport f = files.get(name);
        if (f == null) {
            throw new IllegalArgumentException("no file transport named " + name);
        }
        return f;
    }
}
