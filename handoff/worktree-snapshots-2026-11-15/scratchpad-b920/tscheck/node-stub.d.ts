// Minimal stand-in for @types/node (not installed locally): only what the SDK sources use. Compile check only.
declare module 'node:crypto' { export function randomUUID(): string; export function createHash(a: string): { update(d: string | Uint8Array): { digest(e: 'hex' | 'base64'): string } }; }
declare module 'node:os' { export function hostname(): string; }
declare namespace NodeJS { type Signals = 'SIGTERM' | 'SIGINT' | 'SIGHUP'; type Timeout = ReturnType<typeof setTimeout>; }
declare const process: { pid: number; once(s: string, h: () => void): void; removeListener(s: string, h: () => void): void; env: Record<string, string | undefined> };
declare const Buffer: { from(v: string | Uint8Array | ArrayBuffer, enc?: string): { toString(enc?: string): string } & Uint8Array };
