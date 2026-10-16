/** Session memory only. No response is written to browser or HTTP storage. */
type Entry = { value: unknown; bytes: number; at: number };
type Flight = { controller: AbortController; promise: Promise<unknown>; consumers: number };
const aborted = () => new DOMException('Inspection request cancelled', 'AbortError');

export class InspectionQueryCache {
  private entries = new Map<string, Entry>();
  private flights = new Map<string, Flight>();
  private views = new Map<string, unknown>();
  private listeners = new Set<() => void>();
  private sessionListeners = new Set<() => void>();
  private bytes = 0;
  private generation = 0;
  private context = '';
  sessionEpoch = 0;
  private selection = '';
  private revisions = new Map<string, string>();
  readonly maxEntries = 128;
  readonly maxBytes = 8 * 1024 * 1024;
  constructor(private readonly freshMs = 15_000, private readonly now = () => Date.now()) {}
  get epoch() { return this.generation; }
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  subscribeSession = (listener: () => void) => { this.sessionListeners.add(listener); return () => { this.sessionListeners.delete(listener); }; };
  private notify() { for (const listener of this.listeners) listener(); }
  clear(preserveViews = false) {
    this.generation++;
    for (const flight of this.flights.values()) flight.controller.abort();
    this.flights.clear(); this.entries.clear(); this.revisions.clear(); this.bytes = 0;
    if (!preserveViews) this.views.clear();
    this.notify();
  }
  resetSession() {
    this.context = ''; this.sessionEpoch++; this.clear();
    for (const listener of this.sessionListeners) listener();
  }
  setContext(context: string) {
    if (this.context === context) return;
    const hadContext = Boolean(this.context);
    this.context = context;
    this.clear();
    if (hadContext) { this.sessionEpoch++; for (const listener of this.sessionListeners) listener(); }
  }
  select(packageId: string, environment: string) {
    const next = JSON.stringify([packageId, environment]);
    if (this.selection !== next) { this.selection = next; this.clear(); }
  }
  getView<T>(key: string): T | undefined { return this.views.get(key) as T | undefined; }
  setView<T>(key: string, value: T) {
    this.views.delete(key); this.views.set(key, value);
    while (this.views.size > 32) this.views.delete(this.views.keys().next().value!);
  }
  private key(path: string) { return JSON.stringify([this.context, this.selection, path]); }
  private remove(key: string) {
    const entry = this.entries.get(key);
    if (entry) this.bytes -= entry.bytes;
    this.entries.delete(key);
  }
  invalidatePath(path: string) {
    const key = this.key(path);
    this.remove(key);
    this.flights.get(key)?.controller.abort();
    this.flights.delete(key);
  }
  invalidatePackage(packageId: string, exceptPath?: string) {
    const marker = 'packages/' + encodeURIComponent(packageId) + '/';
    for (const key of this.entries.keys()) if (String(JSON.parse(key)[2]).startsWith(marker)) this.remove(key);
    for (const [key, flight] of this.flights) {
      const path = String(JSON.parse(key)[2]);
      if (path.startsWith(marker) && path !== exceptPath) { flight.controller.abort(); this.flights.delete(key); }
    }
    this.views.clear(); this.revisions.delete(packageId);
  }
  revision(packageId: string, revision: string, path?: string) {
    const previous = this.revisions.get(packageId);
    if (previous && previous !== revision) { this.invalidatePackage(packageId, path); this.notify(); }
    this.revisions.set(packageId, revision);
    while (this.revisions.size > 32) this.revisions.delete(this.revisions.keys().next().value!);
  }
  private store(key: string, value: unknown) {
    try {
      const bytes = new TextEncoder().encode(JSON.stringify(value)).byteLength;
      if (bytes > 1024 * 1024) return;
      this.remove(key); this.entries.set(key, { value, bytes, at: this.now() }); this.bytes += bytes;
      while (this.entries.size > this.maxEntries || this.bytes > this.maxBytes) this.remove(this.entries.keys().next().value!);
    } catch { /* Cache failures leave the successful transport response usable. */ }
  }
  async query<T>(path: string, load: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) throw aborted();
    const key = this.key(path);
    const cached = this.entries.get(key);
    if (cached) {
      this.entries.delete(key); this.entries.set(key, cached);
      if (this.now() - cached.at < this.freshMs) return structuredClone(cached.value) as T;
      // A stale display is replaced through subscribers when revalidation completes.
      void this.fetch(key, load).then(() => this.notify(), () => {
        // An aborted older flight must not evict a replacement after invalidation.
        if (this.entries.get(key) === cached) { this.remove(key); this.notify(); }
      });
      return structuredClone(cached.value) as T;
    }
    return this.fetch(key, load, signal);
  }
  private fetch<T>(key: string, load: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
    let flight = this.flights.get(key);
    if (!flight) {
      const epoch = this.generation;
      const controller = new AbortController();
      const created: Flight = { controller, consumers: 0, promise: Promise.resolve() };
      created.promise = Promise.resolve().then(() => {
        if (controller.signal.aborted) throw aborted();
        return load(controller.signal);
      }).then(value => {
        if (this.generation !== epoch || controller.signal.aborted) throw aborted();
        this.store(key, value); return value;
      }).finally(() => { if (this.flights.get(key) === created) this.flights.delete(key); });
      while (this.flights.size >= 16) {
        const oldest = this.flights.keys().next().value!;
        this.flights.get(oldest)!.controller.abort(); this.flights.delete(oldest);
      }
      flight = created; this.flights.set(key, flight);
    }
    const current = flight;
    current.consumers++;
    return new Promise<T>((resolve, reject) => {
      let done = false;
      const finish = () => {
        if (done) return false;
        done = true; signal?.removeEventListener('abort', cancel);
        if (--current.consumers === 0 && this.flights.get(key) === current) {
          current.controller.abort(); this.flights.delete(key);
        }
        return true;
      };
      const cancel = () => { if (finish()) reject(aborted()); };
      signal?.addEventListener('abort', cancel, { once: true });
      current.promise.then(value => { if (finish()) resolve(structuredClone(value) as T); }, error => { if (finish()) reject(error); });
      if (signal?.aborted) cancel();
    });
  }
}

export const inspectionCache = new InspectionQueryCache();
