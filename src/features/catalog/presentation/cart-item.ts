import { CART_MAX_QUANTITY, type CartItemInput, type CartPrice } from "@/shared/cart";
import type { StoreProduct } from "../model";

function parseCartPrice(value: string): CartPrice | null {
  if (!/^\d+\.\d{2}$/.test(value)) return null;
  const amountMinor = Number(value.replace(".", ""));
  return Number.isSafeInteger(amountMinor) && amountMinor > 0
    ? { currency: "ARS", amountMinor }
    : null;
}

export function toCartItem(product: StoreProduct): CartItemInput {
  const detail = product.kind === "BASE"
    ? "Producto base"
    : `Combo de ${product.comboUnits} unidades`;

  return {
    id: product.id,
    name: product.name,
    detail,
    unitPrice: parseCartPrice(product.price),
    quantity: 1,
    maxQuantity: Math.max(1, Math.min(CART_MAX_QUANTITY, product.maxQuantity)),
  };
}
