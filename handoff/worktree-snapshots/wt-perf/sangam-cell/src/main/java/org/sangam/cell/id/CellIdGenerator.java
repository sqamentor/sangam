// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.id;

/**
 * Port: cell-aware id generation for engine and product rows. Engine-neutral (no Operaton import); the Operaton
 * binding is {@link OperatonCellIdGenerator}.
 */
public interface CellIdGenerator {

    CellId cell();

    String nextId();

    static CellIdGenerator forCell(CellId cell) {
        return new CellIdGenerator() {
            @Override
            public CellId cell() {
                return cell;
            }

            @Override
            public String nextId() {
                return GlobalIds.newId(cell);
            }
        };
    }
}
