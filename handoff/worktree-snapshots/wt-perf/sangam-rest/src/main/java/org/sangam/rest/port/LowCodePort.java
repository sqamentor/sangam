// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

/**
 * Low-code pages (R77, EC-03), named queries (R25) and data objects (R78, EC-06). Pages and queries are authorized on
 * the server; every query goes through the same tenant and permission checks as the underlying port.
 */
public interface LowCodePort {

    /** kind: heading | text | list | detail | kpi | action. */
    record PageBlock(String id, String kind, String title, String text, String query, List<String> columns,
                     String dataObject, String processKey) {
    }

    /** state: DRAFT | ACTIVE | RETIRED. */
    record PageModel(String id, String key, String title, Integer version, String state, List<PageBlock> blocks) {
    }

    record PageSummary(String id, String key, String title, int version, String state) {
    }

    /** type: string | number | boolean | date | reference | json. */
    record DataObjectField(String name, String type, boolean required, String reference, boolean sensitive) {
    }

    /** backing: DB | REST (REST-backed writes use R36 operation identity through R71). */
    record DataObjectDef(String id, String key, String name, String backing, String endpointRef, Integer version,
                         List<DataObjectField> fields) {
    }

    List<PageSummary> pages(RequestContext ctx);

    PageModel page(RequestContext ctx, String key);

    PageModel createPage(RequestContext ctx, PageModel page);

    PageModel updatePage(RequestContext ctx, String key, PageModel page);

    Page<Map<String, Object>> query(RequestContext ctx, String name, Map<String, String> params, Page.PageRequest page);

    List<DataObjectDef> dataObjects(RequestContext ctx);

    DataObjectDef createDataObject(RequestContext ctx, DataObjectDef def);

    DataObjectDef updateDataObject(RequestContext ctx, String id, DataObjectDef def);

    Page<Map<String, Object>> records(RequestContext ctx, String key, String q, Page.PageRequest page);

    Map<String, Object> createRecord(RequestContext ctx, String key, Map<String, Object> record);
}
