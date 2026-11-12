// SPDX-License-Identifier: Apache-2.0
/**
 * M-044 (R13): links from a recorded trace id to the installation's trace viewer. The viewer's URL is a runtime
 * setting (/config.json traceUrlTemplate, config.ts) with the placeholder {traceId}; one image serves every
 * installation, so nothing is baked into the build. No template, an invalid trace id, or a template that does not
 * give an http(s) URL means no link: the id is still shown as text.
 */
import { config } from '../config';

/** A W3C trace id: 32 lowercase hex digits, not all zero (W3C Trace Context §3.2.2.3). */
export function isTraceId(id: string | undefined | null): id is string {
  return !!id && /^[0-9a-f]{32}$/.test(id) && !/^0+$/.test(id);
}

/** The trace id inside a traceparent header value (version-traceid-spanid-flags), when it is one. */
export function traceIdOf(traceparent: string | undefined | null): string | undefined {
  const id = traceparent?.split('-')[1]?.toLowerCase();
  return isTraceId(id) ? id : undefined;
}

/** The trace viewer URL of a trace id, or undefined when there is no usable template or id. */
export function traceUrl(traceId: string | undefined | null, template: string = config().traceUrlTemplate): string | undefined {
  const id = traceId?.toLowerCase();
  if (!template || !isTraceId(id) || !template.includes('{traceId}')) return undefined;
  const url = template.split('{traceId}').join(encodeURIComponent(id));
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : undefined;
  } catch {
    return undefined;
  }
}
