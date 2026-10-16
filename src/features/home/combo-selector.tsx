"use client";

import { useState } from "react";
import { offers } from "./content";
import layout from "@/shared/ui/layout.module.css";

export function ComboSelector() {
  const [selectedUnits, setSelectedUnits] = useState(2);
  const selectedOffer = offers.find((offer) => offer.units === selectedUnits) ?? offers[0];

  return (
    <section className={`${layout.container} combo-section`} id="combos" data-reveal>
      <div className="section-heading">
        <span className="section-index" aria-hidden="true">02 / 05</span>
        <p className="eyebrow"><span className="eyebrow__line" /> ELEGÍ TU COMBO</p>
        <h2>Empezá por la opción<br /><em>que mejor te quede.</em></h2>
        <p className="section-heading__text">Seleccioná una duración. Los precios se publicarán cuando estén confirmados.</p>
      </div>
      <div className="combo-layout">
        <div className="combo-options" role="group" aria-label="Seleccionar combo">
          {offers.map((offer) => (
            <button
              className={`combo-card${selectedUnits === offer.units ? " combo-card--selected" : ""}`}
              key={offer.units}
              type="button"
              aria-pressed={selectedUnits === offer.units}
              onClick={() => setSelectedUnits(offer.units)}
            >
              <span className="combo-card__radio" aria-hidden="true">{selectedUnits === offer.units ? "✓" : ""}</span>
              <span className="combo-card__title">{offer.label}</span>
              <span className="combo-card__note">{offer.note}</span>
              <span className="combo-card__price">Precio a confirmar</span>
            </button>
          ))}
        </div>
        <aside className="order-summary" aria-live="polite">
          <div className="order-summary__top">
            <p className="eyebrow">TU SELECCIÓN</p>
            <span className="order-summary__icon" aria-hidden="true">✳</span>
          </div>
          <h3>{selectedOffer.label}<br /><em>de acompañamiento</em></h3>
          <p>{selectedOffer.note}</p>
          <div className="order-summary__line"><span>Plan digital + seguimiento</span><b>Incluido</b></div>
          <div className="order-summary__line"><span>Cobertura de envío</span><b>A confirmar</b></div>
          <div className="order-summary__total"><span>Precio final</span><b>A confirmar</b></div>
          <button className="button button--disabled" type="button" disabled>Compra online próximamente</button>
          <small>El checkout se habilitará al confirmar precios, medios de pago y cobertura de envío.</small>
          <small>Información dirigida a mayores de 18 años. Consultá el rótulo vigente antes de usar el producto.</small>
        </aside>
      </div>
    </section>
  );
}
