"use client";

import Link from "next/link";
import { useCart } from "./CartProvider";
import styles from "./cart.module.css";

export function CartLink() {
  const { summary, hydrated } = useCart();
  const count = hydrated ? summary.itemCount : 0;
  return (
    <Link className={styles.cartLink} href="/carrito" aria-label={count === 0 ? "Carrito" : `Carrito, ${count} ${count === 1 ? "producto" : "productos"}`}>
      <span aria-hidden="true">Carrito</span>
      {count > 0 && <span className={styles.cartCount} aria-hidden="true">{count}</span>}
    </Link>
  );
}
