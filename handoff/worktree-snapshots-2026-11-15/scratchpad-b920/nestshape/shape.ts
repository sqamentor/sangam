interface Type<T = any> extends Function { new (...args: any[]): T; }
interface Abstract<T> extends Function { prototype: T; }
type InjectionToken<T = any> = string | symbol | Type<T> | Abstract<T> | Function;
interface ClassProvider<T = any> { provide: InjectionToken; useClass: Type<T>; }
interface ValueProvider<T = any> { provide: InjectionToken; useValue: T; inject?: never; }
interface FactoryProvider<T = any> { provide: InjectionToken; useFactory: (...args: any[]) => T | Promise<T>; inject?: Array<InjectionToken>; }
type Provider<T = any> = Type<any> | ClassProvider<T> | ValueProvider<T> | FactoryProvider<T>;
interface ForwardReference<T = any> { forwardRef: T; }
interface ModuleMetadata { imports?: Array<Type<any> | DynamicModule | Promise<DynamicModule> | ForwardReference>; controllers?: Type<any>[]; providers?: Provider[]; exports?: Array<DynamicModule | string | symbol | Provider | ForwardReference | Abstract<any> | Function>; }
interface DynamicModule extends ModuleMetadata { module: Type<any>; global?: boolean; }
interface SangamDynamicModule { module: new (...args: any[]) => any; global?: boolean; imports?: any[]; providers?: any[]; exports?: any[]; }
declare const m: SangamDynamicModule;
const d: DynamicModule = m;
const meta: ModuleMetadata = { imports: [m] };
export { d, meta };
