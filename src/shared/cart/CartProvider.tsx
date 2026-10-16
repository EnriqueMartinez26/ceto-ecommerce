"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import {
  addCartItem,
  clearCart,
  removeCartItem,
  setCartItemQuantity,
  summarizeCart,
  type CartItemInput,
  type CartLine,
  type CartState,
  type CartSummary,
} from "./cart-model";
import { CART_STORAGE_KEY, getBrowserCartStorage, parseCartStorageValue, readCart, writeCart } from "./cart-storage";
import styles from "./cart.module.css";

type State = { lines: CartLine[]; announcement: string };
type Action =
  | { type: "restore"; lines: CartLine[] }
  | { type: "add"; item: CartItemInput }
  | { type: "quantity"; id: string; quantity: number }
  | { type: "remove"; id: string }
  | { type: "clear" };

function reducer(state: State, action: Action): State {
  if (action.type === "restore") return { lines: action.lines, announcement: "" };
  const update = action.type === "add"
    ? addCartItem(state.lines, action.item)
    : action.type === "quantity"
      ? setCartItemQuantity(state.lines, action.id, action.quantity)
      : action.type === "remove"
        ? removeCartItem(state.lines, action.id)
        : clearCart(state.lines);
  return { lines: update.lines, announcement: update.message };
}

export type CartContextValue = {
  lines: CartState;
  summary: CartSummary;
  hydrated: boolean;
  persistence: "pending" | "saved" | "unavailable";
  announcement: string;
  addItem: (item: CartItemInput) => void;
  setQuantity: (id: string, quantity: number) => void;
  removeItem: (id: string) => void;
  emptyCart: () => void;
};

const CartContext = createContext<CartContextValue | null>(null);
let cartHydrated = false;
const hydrationListeners = new Set<() => void>();
let cartPersistence: CartContextValue["persistence"] = "pending";
const persistenceListeners = new Set<() => void>();

function subscribeHydration(listener: () => void) {
  hydrationListeners.add(listener);
  return () => hydrationListeners.delete(listener);
}

function getHydrationSnapshot() {
  return cartHydrated;
}

function getServerHydrationSnapshot() {
  return false;
}

function subscribePersistence(listener: () => void) {
  persistenceListeners.add(listener);
  return () => persistenceListeners.delete(listener);
}

function getPersistenceSnapshot() {
  return cartPersistence;
}

function getServerPersistenceSnapshot() {
  return "pending" as const;
}

function setPersistenceStatus(status: CartContextValue["persistence"]) {
  if (cartPersistence === status) return;
  cartPersistence = status;
  persistenceListeners.forEach((listener) => listener());
}

function completeCartHydration() {
  if (cartHydrated) return;
  cartHydrated = true;
  hydrationListeners.forEach((listener) => listener());
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, { lines: [], announcement: "" });
  const hydrated = useSyncExternalStore(subscribeHydration, getHydrationSnapshot, getServerHydrationSnapshot);
  const persistence = useSyncExternalStore(subscribePersistence, getPersistenceSnapshot, getServerPersistenceSnapshot);

  useEffect(() => {
    const storage = getBrowserCartStorage();
    if (storage) dispatch({ type: "restore", lines: readCart(storage) });
    completeCartHydration();
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    const storage = getBrowserCartStorage();
    setPersistenceStatus(storage && writeCart(storage, state.lines) ? "saved" : "unavailable");
  }, [hydrated, state.lines]);

  useEffect(() => {
    if (!hydrated) return;
    const syncFromOtherTab = (event: StorageEvent) => {
      if (event.key !== null && event.key !== CART_STORAGE_KEY) return;
      dispatch({ type: "restore", lines: parseCartStorageValue(event.key === null ? null : event.newValue) });
    };
    window.addEventListener("storage", syncFromOtherTab);
    return () => window.removeEventListener("storage", syncFromOtherTab);
  }, [hydrated]);

  const addItem = useCallback((item: CartItemInput) => dispatch({ type: "add", item }), []);
  const setQuantity = useCallback((id: string, quantity: number) => dispatch({ type: "quantity", id, quantity }), []);
  const removeItem = useCallback((id: string) => dispatch({ type: "remove", id }), []);
  const emptyCart = useCallback(() => dispatch({ type: "clear" }), []);
  const summary = useMemo(() => summarizeCart(state.lines), [state.lines]);
  const value = useMemo<CartContextValue>(() => ({
    lines: state.lines,
    summary,
    hydrated,
    persistence,
    announcement: state.announcement,
    addItem,
    setQuantity,
    removeItem,
    emptyCart,
  }), [state, summary, hydrated, persistence, addItem, setQuantity, removeItem, emptyCart]);

  return (
    <CartContext.Provider value={value}>
      {children}
      <span className={styles.srOnly} aria-live="polite" aria-atomic="true">{state.announcement}</span>
    </CartContext.Provider>
  );
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error("useCart debe usarse dentro de CartProvider.");
  return value;
}
