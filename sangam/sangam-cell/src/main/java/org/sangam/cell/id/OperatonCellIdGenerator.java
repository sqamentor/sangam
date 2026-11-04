// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.id;

import org.operaton.bpm.engine.impl.cfg.IdGenerator;

/** Operaton {@link IdGenerator} producing {@code <cellId>.<uuidv7>} ids (replaces StrongUuidGenerator in a cell). */
public final class OperatonCellIdGenerator implements IdGenerator {

    private final CellIdGenerator delegate;

    public OperatonCellIdGenerator(CellIdGenerator delegate) {
        this.delegate = delegate;
    }

    public OperatonCellIdGenerator(CellId cell) {
        this(CellIdGenerator.forCell(cell));
    }

    @Override
    public String getNextId() {
        return delegate.nextId();
    }
}
