import { CART_MAX_LINES, CART_MAX_QUANTITY, isValidCartLine, type CartLine, type CartState } from "./cart-model";

export const CART_STORAGE_KEY = "ceto.cart.preview";
export const CART_STORAGE_VERSION = 2;

export interface CartStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

type StoredCart = {
  version: number;
  items: unknown;
};

export function parseCartStorageValue(value: string | null): CartLine[] {
  if (value === null) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return [];
    const stored = parsed as Partial<StoredCart>;
    if ((stored.version !== 1 && stored.version !== CART_STORAGE_VERSION) || !Array.isArray(stored.items)) return [];

    const lines: CartLine[] = [];
    const ids = new Set<string>();
    for (const item of stored.items) {
      const candidate = stored.version === 1 && typeof item === "object" && item !== null && !Array.isArray(item) &&
        !("maxQuantity" in item)
        ? { ...item, maxQuantity: CART_MAX_QUANTITY }
        : item;
      if (!isValidCartLine(candidate) || ids.has(candidate.id) || lines.length >= CART_MAX_LINES) continue;
      ids.add(candidate.id);
      lines.push(candidate);
    }
    return lines;
  } catch {
    return [];
  }
}

export function readCart(storage: CartStorage): CartLine[] {
  try {
    return parseCartStorageValue(storage.getItem(CART_STORAGE_KEY));
  } catch {
    return [];
  }
}

export function writeCart(storage: CartStorage, lines: CartState): boolean {
  try {
    storage.setItem(CART_STORAGE_KEY, JSON.stringify({ version: CART_STORAGE_VERSION, items: lines }));
    return true;
  } catch {
    return false;
  }
}

export function getBrowserCartStorage(): CartStorage | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}
