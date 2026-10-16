"use client";

import Link from "next/link";
import { formatArs } from "@/shared/format/ars";
import { useCart, type CartPrice } from "@/shared/cart";
import styles from "./cart.module.css";

function formatCartPrice(price: CartPrice): string {
  const whole = Math.floor(price.amountMinor / 100);
  const cents = String(price.amountMinor % 100).padStart(2, "0");
  return formatArs(`${whole}.${cents}`);
}

export function CartView() {
  const { lines, summary, hydrated, persistence, setQuantity, removeItem, emptyCart } = useCart();

  return (
    <main className={styles.page}>
      <a className="skip-link" href="#cart-content">Saltar al contenido principal</a>
      <header className={styles.header}>
        <Link className={styles.backLink} href="/productos">← Seguir explorando</Link>
      </header>
      <section className={styles.intro} id="cart-content" tabIndex={-1} aria-labelledby="cart-title">
        <p className={styles.kicker}>TU SELECCIÓN</p>
        <h1 id="cart-title">Tu carrito<span>.</span></h1>
        <p>Revisá las opciones que elegiste antes de continuar.</p>
      </section>

      <p className={styles.previewNotice} role="note">
        Vista previa: no genera un pedido ni reserva productos.{" "}
        {persistence === "saved"
          ? "Se guarda en este navegador."
          : persistence === "unavailable"
            ? "Este navegador no permite guardar el carrito; los cambios pueden perderse al recargar o cerrar la página."
            : "Comprobando si el navegador puede guardar tu selección…"}
      </p>

      {!hydrated ? (
        <p className={styles.state} role="status">Cargando tu carrito…</p>
      ) : lines.length === 0 ? (
        <section className={styles.emptyState} aria-labelledby="empty-title">
          <p className={styles.kicker}>TODAVÍA NO ELEGISTE NADA</p>
          <h2 id="empty-title">Tu carrito está vacío.</h2>
          <p>Cuando agregues una opción, la vas a encontrar acá.</p>
          <Link className={styles.primaryLink} href="/productos">Ver productos</Link>
        </section>
      ) : (
        <div className={styles.columns}>
          <section className={styles.itemsSection} aria-label="Productos en tu carrito">
            <div className={styles.itemsHeading}>
              <h2>Lo que elegiste</h2>
              <button className={styles.textButton} type="button" onClick={emptyCart}>Vaciar carrito</button>
            </div>
            <ul className={styles.items}>
              {lines.map((line) => (
                <li className={styles.item} key={line.id}>
                  <div className={styles.itemCopy}>
                    <h3>{line.name}</h3>
                    {line.detail && <p>{line.detail}</p>}
                    <p className={styles.priceLine}>
                      Precio unitario: {line.unitPrice ? formatCartPrice(line.unitPrice) : "A confirmar"}
                    </p>
                  </div>
                  <div className={styles.itemActions}>
                    <div className={styles.quantity} role="group" aria-label={`Cantidad de ${line.name}`}>
                      <button
                        type="button"
                        aria-label={`Disminuir cantidad de ${line.name}`}
                        disabled={line.quantity <= 1}
                        onClick={() => setQuantity(line.id, line.quantity - 1)}
                      >−</button>
                      <span>{line.quantity}</span>
                      <button
                        type="button"
                        aria-label={`Aumentar cantidad de ${line.name}`}
                        disabled={line.quantity >= line.maxQuantity}
                        onClick={() => setQuantity(line.id, line.quantity + 1)}
                      >+</button>
                    </div>
                    <button className={styles.removeButton} type="button" onClick={() => removeItem(line.id)}>
                      Quitar <span className={styles.srOnly}>{line.name}</span>
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </section>

          <aside className={styles.summary} aria-labelledby="summary-title">
            <p className={styles.kicker}>RESUMEN</p>
            <h2 id="summary-title">Antes de continuar</h2>
            <dl className={styles.summaryRows}>
              <div><dt>Opciones</dt><dd>{summary.lineCount}</dd></div>
              <div><dt>Unidades</dt><dd>{summary.itemCount}</dd></div>
              <div className={styles.total}><dt>{summary.pricing === "confirmed" ? "Subtotal" : "Precio"}</dt>
                <dd>{summary.subtotal ? formatCartPrice(summary.subtotal) : "A confirmar"}</dd></div>
            </dl>
            <button className={styles.disabledButton} type="button" disabled>Compra online próximamente</button>
            <p className={styles.summaryNote}>Confirmaremos precios, medios de pago y envío antes de habilitar la compra.</p>
          </aside>
        </div>
      )}
    </main>
  );
}
