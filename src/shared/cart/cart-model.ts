export const CART_MAX_LINES = 20;
export const CART_MAX_QUANTITY = 99;

export type CartPrice = {
  currency: "ARS";
  amountMinor: number;
};

export type CartLine = {
  id: string;
  name: string;
  detail: string | null;
  quantity: number;
  maxQuantity: number;
  unitPrice: CartPrice | null;
};

export type CartItemInput = Omit<CartLine, "quantity"> & { quantity?: number };
export type CartState = readonly CartLine[];

export type CartSummary = {
  lineCount: number;
  itemCount: number;
  pricing: "pending" | "confirmed";
  subtotal: CartPrice | null;
};

export type CartUpdate = {
  lines: CartLine[];
  changed: boolean;
  message: string;
};

function isValidText(value: string, maxLength: number): boolean {
  return value.trim().length > 0 && value.length <= maxLength;
}

function isValidPrice(value: unknown): value is CartPrice | null {
  if (value === null) return true;
  if (typeof value !== "object" || Array.isArray(value)) return false;
  const price = value as Partial<CartPrice>;
  return price.currency === "ARS" && Number.isSafeInteger(price.amountMinor) && price.amountMinor! >= 0;
}

function samePrice(left: CartPrice | null, right: CartPrice | null): boolean {
  return left === null ? right === null : right !== null &&
    left.currency === right.currency && left.amountMinor === right.amountMinor;
}

export function isValidCartLine(value: unknown): value is CartLine {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const candidate = value as Partial<CartLine>;
  return typeof candidate.id === "string" && isValidText(candidate.id, 120) &&
    typeof candidate.name === "string" && isValidText(candidate.name, 200) &&
    (candidate.detail === null || (typeof candidate.detail === "string" && candidate.detail.length <= 240)) &&
    Number.isSafeInteger(candidate.maxQuantity) && candidate.maxQuantity! >= 1 && candidate.maxQuantity! <= CART_MAX_QUANTITY &&
    Number.isSafeInteger(candidate.quantity) && candidate.quantity! >= 1 && candidate.quantity! <= candidate.maxQuantity! &&
    isValidPrice(candidate.unitPrice);
}

function validInput(item: CartItemInput): boolean {
  const quantity = item.quantity ?? 1;
  return isValidText(item.id, 120) && isValidText(item.name, 200) &&
    (item.detail === null || (typeof item.detail === "string" && item.detail.length <= 240)) &&
    Number.isSafeInteger(item.maxQuantity) && item.maxQuantity >= 1 && item.maxQuantity <= CART_MAX_QUANTITY &&
    Number.isSafeInteger(quantity) && quantity >= 1 && quantity <= item.maxQuantity && isValidPrice(item.unitPrice);
}

export function addCartItem(lines: CartState, item: CartItemInput): CartUpdate {
  if (!validInput(item)) return { lines: [...lines], changed: false, message: "No se pudo agregar esta opción al carrito." };

  const quantity = item.quantity ?? 1;
  const existingIndex = lines.findIndex((line) => line.id === item.id);
  if (existingIndex < 0 && lines.length >= CART_MAX_LINES) {
    return { lines: [...lines], changed: false, message: `El carrito admite hasta ${CART_MAX_LINES} opciones distintas.` };
  }

  const next = [...lines];
  if (existingIndex >= 0) {
    const existing = next[existingIndex];
    const maxQuantity = Math.min(existing.maxQuantity, item.maxQuantity);
    const nextQuantity = Math.min(maxQuantity, existing.quantity + quantity);
    next[existingIndex] = {
      ...existing,
      name: item.name,
      detail: item.detail,
      unitPrice: item.unitPrice,
      maxQuantity,
      quantity: nextQuantity,
    };
    const changed = nextQuantity !== existing.quantity || maxQuantity !== existing.maxQuantity ||
      existing.name !== item.name || existing.detail !== item.detail ||
      !samePrice(existing.unitPrice, item.unitPrice);
    const reachedCap = existing.quantity + quantity > maxQuantity;
    const message = reachedCap
      ? `La cantidad de ${item.name} quedó limitada a ${maxQuantity} unidades.`
      : changed
        ? `Cantidad de ${item.name} actualizada en el carrito.`
        : `${item.name} ya está en el carrito, con el máximo de ${maxQuantity} unidades.`;
    return { lines: next, changed, message };
  }

  next.push({ ...item, quantity });
  return { lines: next, changed: true, message: `${item.name} se agregó al carrito.` };
}

export function setCartItemQuantity(lines: CartState, id: string, quantity: number): CartUpdate {
  const index = lines.findIndex((line) => line.id === id);
  if (index < 0) return { lines: [...lines], changed: false, message: "" };
  if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > lines[index].maxQuantity) {
    return { lines: [...lines], changed: false, message: `La cantidad máxima de este producto es ${lines[index].maxQuantity}.` };
  }
  if (lines[index].quantity === quantity) {
    return { lines: [...lines], changed: false, message: "" };
  }
  const next = [...lines];
  next[index] = { ...next[index], quantity };
  return { lines: next, changed: true, message: `Cantidad de ${next[index].name} actualizada.` };
}

export function removeCartItem(lines: CartState, id: string): CartUpdate {
  const removed = lines.find((line) => line.id === id);
  if (!removed) return { lines: [...lines], changed: false, message: "" };
  return {
    lines: lines.filter((line) => line.id !== id),
    changed: true,
    message: `${removed.name} se quitó del carrito.`,
  };
}

export function clearCart(lines: CartState): CartUpdate {
  if (lines.length === 0) return { lines: [], changed: false, message: "" };
  return { lines: [], changed: true, message: "Se vació el carrito." };
}

export function summarizeCart(lines: CartState): CartSummary {
  const itemCount = lines.reduce((total, line) => total + line.quantity, 0);
  if (lines.length === 0 || lines.some((line) => line.unitPrice === null)) {
    return { lineCount: lines.length, itemCount, pricing: "pending", subtotal: null };
  }

  let subtotalMinor = 0;
  for (const line of lines) {
    const lineTotal = line.unitPrice!.amountMinor * line.quantity;
    if (!Number.isSafeInteger(lineTotal) || !Number.isSafeInteger(subtotalMinor + lineTotal)) {
      return { lineCount: lines.length, itemCount, pricing: "pending", subtotal: null };
    }
    subtotalMinor += lineTotal;
  }

  return {
    lineCount: lines.length,
    itemCount,
    pricing: "confirmed",
    subtotal: { currency: "ARS", amountMinor: subtotalMinor },
  };
}
