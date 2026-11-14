// Scratch-only stand-in for @types/node (compile check; never committed).
declare module 'node:crypto' { export function randomUUID(): string; }
declare module 'node:os' { export function hostname(): string; }
declare module 'node:fs' { export function readFileSync(p: string | URL, enc: string): string; export function existsSync(p: string | URL): boolean; }
declare module 'node:path' { export function dirname(p: string): string; export function join(...p: string[]): string; }
declare module 'node:url' { export function fileURLToPath(u: string | URL): string; }
declare module 'node:test' {
  export function describe(name: string, fn: () => void | Promise<void>): void;
  export function it(name: string, fn: () => void | Promise<void>): void;
  export function before(fn: () => void | Promise<void>): void;
  export function after(fn: () => void | Promise<void>): void;
  export function beforeEach(fn: () => void | Promise<void>): void;
  export function afterEach(fn: () => void | Promise<void>): void;
}
declare module 'node:assert/strict' {
  function assert(v: unknown, msg?: string): asserts v;
  namespace assert {
    function ok(v: unknown, msg?: string): asserts v;
    function equal<T>(a: unknown, b: T, msg?: string): asserts a is T;
    function notEqual(a: unknown, b: unknown, msg?: string): void;
    function deepEqual<T>(a: unknown, b: T, msg?: string): asserts a is T;
    function notDeepEqual(a: unknown, b: unknown, msg?: string): void;
    function throws(fn: () => unknown, e?: unknown, msg?: string): void;
    function rejects(p: Promise<unknown> | (() => Promise<unknown>), e?: unknown, msg?: string): Promise<void>;
    function doesNotThrow(fn: () => unknown, msg?: string): void;
    function match(s: string, r: RegExp, msg?: string): void;
    function fail(msg?: string): never;
    function strictEqual<T>(a: unknown, b: T, msg?: string): asserts a is T;
  }
  export default assert;
}
declare namespace NodeJS { type Signals = 'SIGTERM' | 'SIGINT' | 'SIGHUP'; type Timeout = ReturnType<typeof setTimeout>; }
declare const process: { pid: number; once(s: string, h: () => void): void; removeListener(s: string, h: () => void): void;
  env: Record<string, string | undefined>; exitCode: number | undefined; hrtime: { bigint(): bigint }; stdout: { write(s: string): boolean }; stderr: { write(s: string): boolean } };
declare const Buffer: { from(v: string | Uint8Array | ArrayBuffer, enc?: string): { toString(enc?: string): string } & Uint8Array };
