import { Capacitor } from "@capacitor/core";
import { Preferences } from "@capacitor/preferences";

/**
 * The one key/value primitive under storage.ts and device.ts.
 *
 * Why it exists: `@capacitor/preferences` is async, but every read in this app
 * happens on a synchronous path that cannot await — `useState(initialRoster)`
 * at mount, and `award`/`spend` inside a pointerdown handler (invariant 1:
 * feedback fires before React commits). So on native we hydrate ONCE at boot
 * into a memory cache, serve every read from it synchronously, and write
 * through fire-and-forget.
 *
 * On the web there is no async problem, so the web path IS localStorage,
 * untouched. That keeps `vite dev`, the PWA and the whole test suite on exactly
 * the code they ran before — the native path is additive, not a rewrite.
 *
 * Native storage matters beyond ergonomics: a WebView's localStorage is
 * evictable by the OS under disk pressure. Preferences is not (NSUserDefaults /
 * SharedPreferences), and it is what iCloud Key-Value Store and Android Auto
 * Backup actually back up.
 */

const cache = new Map<string, string>();

function isNative(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}

/**
 * Pull every stored key into memory. MUST be awaited before the app mounts on
 * native; a no-op on web. Uses `Preferences.keys()` rather than a registry of
 * key names, so adding a key somewhere never needs a matching edit here.
 */
export async function hydrateKv(): Promise<void> {
  if (!isNative()) return;
  try {
    const { keys } = await Preferences.keys();
    const pairs = await Promise.all(
      keys.map(async (k) => [k, (await Preferences.get({ key: k })).value] as const)
    );
    for (const [k, v] of pairs) if (v !== null) cache.set(k, v);
  } catch {
    // First launch, or a storage fault. An empty cache is a correct empty
    // roster; the app opens on "Qui joue ?" rather than failing to boot.
  }
}

export function getItem(key: string): string | null {
  if (isNative()) return cache.get(key) ?? null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function setItem(key: string, value: string): void {
  if (isNative()) {
    // Cache first so the very next synchronous read sees it, then persist.
    cache.set(key, value);
    void Preferences.set({ key, value }).catch(() => {
      /* disk full / locked: this session still holds the value in memory */
    });
    return;
  }
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode / quota — see storage.ts callers, all tolerate this */
  }
}

export function removeItem(key: string): void {
  if (isNative()) {
    cache.delete(key);
    void Preferences.remove({ key }).catch(() => {
      /* ignore */
    });
    return;
  }
  try {
    localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}
