// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.id;

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * Process-engine plugin installing {@link OperatonCellIdGenerator}. It must be registered <em>after</em>
 * {@code SangamProcessEnginePlugin} (which sets StrongUuidGenerator in preInit), e.g.
 * {@code SangamEngineFactory.build(ds, settings, url, List.of(new CellIdGeneratorPlugin(cell)))}; plugins run in order,
 * so this preInit wins. See sangam-cell README "Integration notes" IR-1.
 */
public final class CellIdGeneratorPlugin extends AbstractProcessEnginePlugin {

    private final CellId cell;

    public CellIdGeneratorPlugin(CellId cell) {
        this.cell = cell;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        cfg.setIdGenerator(new OperatonCellIdGenerator(cell));
    }
}
