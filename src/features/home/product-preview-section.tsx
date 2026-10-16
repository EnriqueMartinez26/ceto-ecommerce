"use client";

import { useCart } from "@/shared/cart";
import layout from "@/shared/ui/layout.module.css";

export function ProductPreviewSection() {
  const { addItem } = useCart();

  function addPreviewProduct() {
    addItem({
      id: "ceto-bhb-preview",
      name: "Ceto BHB",
      detail: "Ficha preliminar. Presentación, precio y disponibilidad en validación.",
      unitPrice: null,
      quantity: 1,
      maxQuantity: 99,
    });
  }

  return (
    <section className={`${layout.container} product-section`} id="producto" data-reveal>
      <div className="section-heading">
        <span className="section-index" aria-hidden="true">02 / 05</span>
        <p className="eyebrow"><span className="eyebrow__line" /> PRODUCTO</p>
        <h2>Conocé la ficha<br /><em>en preparación.</em></h2>
        <p className="section-heading__text">Estamos revisando los datos del producto y las condiciones de venta antes de habilitar pedidos.</p>
      </div>
      <div className="product-layout">
        <div className="product-preview-list">
          <article className="product-preview-card">
            <p className="product-preview-card__status">Ficha preliminar</p>
            <h3>Ceto BHB</h3>
            <p>Presentación e información sujetas a validación del rótulo y registro.</p>
            <strong>Precio a confirmar</strong>
          </article>
        </div>
        <aside className="order-summary">
          <div className="order-summary__top">
            <p className="eyebrow">VISTA PREVIA</p>
            <span className="order-summary__icon" aria-hidden="true">✳</span>
          </div>
          <h3>Ceto BHB<br /><em>ficha preliminar</em></h3>
          <p>Esta acción agrega una referencia de demostración al carrito de este navegador.</p>
          <div className="order-summary__line"><span>Precio</span><b>A confirmar</b></div>
          <div className="order-summary__line"><span>Compra y envío</span><b>No disponibles</b></div>
          <button className="button button--primary" type="button" onClick={addPreviewProduct}>
            Probar en el carrito
          </button>
          <small>La vista previa no genera pedidos ni reserva productos. Presentación y disponibilidad pendientes de validación.</small>
          <small>SUPLEMENTA DIETAS INSUFICIENTES. CONSULTE A SU MÉDICO Y/O FARMACÉUTICO</small>
        </aside>
      </div>
    </section>
  );
}
