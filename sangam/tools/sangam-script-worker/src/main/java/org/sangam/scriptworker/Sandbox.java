// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.util.List;

/**
 * Builds the command line that starts one script's interpreter inside an isolation boundary. The process reads the
 * request JSON on stdin and writes the result JSON on stdout ({@link ScriptRunner}). There is deliberately no
 * implementation without isolation in the product: tests supply their own.
 */
public interface Sandbox {

    List<String> command(Language language, ScriptLimits limits);
}
