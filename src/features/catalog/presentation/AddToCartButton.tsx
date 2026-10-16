"use client";

import { useCart } from "@/shared/cart";
import type { CartItemInput } from "@/shared/cart";
import styles from "./catalog.module.css";

type AddToCartButtonProps = {
  item: CartItemInput;
  disabled?: boolean;
  label?: string;
};

export function AddToCartButton({ item, disabled = false, label = "Agregar al carrito de prueba" }: AddToCartButtonProps) {
  const { addItem } = useCart();

  return (
    <button
      className={styles.addButton}
      type="button"
      disabled={disabled}
      onClick={() => addItem(item)}
    >
      {disabled ? "No disponible" : label}
    </button>
  );
}
