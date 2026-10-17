import { planItems, productValidationItems } from "./content";
import layout from "@/shared/ui/layout.module.css";
import styles from "./home.module.css";

export function OfferBand() {
  return (
    <section className="offer-band" aria-label="Elementos mencionados, pendientes de confirmación">
      <ul className={`${layout.container} offer-band__inner`} aria-label="Elementos mencionados en el material recibido; no confirmados como oferta vigente">
        {planItems.map((item) => (
          <li className="benefit-item" key={item.number}>
            <span className="benefit-item__number">{item.number}</span>
            <span className="benefit-item__title">Por confirmar: {item.title}</span>
            <span className="benefit-item__marker" aria-hidden="true">✳</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function PlanSection() {
  return (
    <section className={`${layout.container} plan-section`} id="plan" data-reveal>
      <div className="section-heading">
        <span className="section-index" aria-hidden="true">03 / 05</span>
        <p className="eyebrow"><span className="eyebrow__line" /> PROPUESTA COMPARTIDA</p>
        <h2>Una propuesta.<br /><em>En revisión.</em></h2>
        <p className="section-heading__text">El material recibido menciona cuatro componentes. Su alcance, entrega y condiciones están pendientes de confirmación.</p>
      </div>
      <div className="plan-grid">
        {planItems.map((item) => (
          <article className="plan-card" key={item.number}>
            <span className="plan-card__number">{item.number}</span>
            <span className="plan-card__arrow" aria-hidden="true">↗</span>
            <h3>{item.title}</h3>
            <p>{item.description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

export function ProductInformationSection() {
  return (
    <section className="formula-section" id="informacion-producto" data-reveal>
      <div className={`${layout.container} formula-layout`}>
        <div className="section-heading section-heading--light">
        <span className="section-index" aria-hidden="true">04 / 05</span>
          <p className="eyebrow"><span className="eyebrow__line" /> INFORMACIÓN DEL PRODUCTO</p>
          <h2>Ficha preliminar.<br /><em>En validación.</em></h2>
          <p className={`${styles.productValidationText} section-heading__text`}>El material compartido identifica el producto como Ceto BHB. La denominación oficial, la presentación, la composición y el modo de uso deben validarse contra el rótulo correspondiente antes de publicar una ficha comercial.</p>
        </div>
        <div className="validation-grid" role="list" aria-label="Datos pendientes de validación">
          {productValidationItems.map((item) => (
            <div className="validation-card" key={item.number} role="listitem">
              <span>{item.number}</span>
              <b>{item.label}</b>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

export function FaqSection() {
  return (
    <section className={`${layout.container} faq-section`} id="preguntas" data-reveal>
      <div className="section-heading">
        <span className="section-index" aria-hidden="true">05 / 05</span>
        <p className="eyebrow"><span className="eyebrow__line" /> INFORMACIÓN PRELIMINAR</p>
        <h2>Antes de <em>consultar.</em></h2>
      </div>
      <div className="faq-list">
        <details>
          <summary><span className="faq-index" aria-hidden="true">01</span>¿Puedo comprar desde esta página?</summary>
          <p>No. El carrito disponible es una demostración: no procesa pagos, no reserva stock ni genera pedidos. El precio y la disponibilidad todavía no están confirmados.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">02</span>¿Qué información del producto está confirmada?</summary>
          <p>El material recibido lo identifica como Ceto BHB. La denominación oficial, presentación, composición, modo de uso y rótulo están pendientes de validación.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">03</span>¿Dónde voy a encontrar la composición y el modo de uso?</summary>
          <p>Se podrán publicar cuando se hayan verificado contra el rótulo y la información oficial aplicables. Por ahora no se presentan como datos confirmados.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">04</span>¿Qué otros elementos menciona la propuesta?</summary>
          <p>El material compartido menciona una guía de alimentación, una propuesta de actividad en casa y acompañamiento. Su contenido, alcance y condiciones están pendientes de confirmación.</p>
        </details>
      </div>
    </section>
  );
}

export function SiteFooter() {
  return (
    <footer className="site-footer" id="contacto">
      <div className={`${layout.container} footer-top`}>
        <a className="wordmark wordmark--footer" href="#inicio">
          <span className="wordmark__text">MEJOR VIDA <b>CETO</b></span>
        </a>
        <p>Información clara.<br /><em>Antes de cada paso.</em></p>
      <a className="footer-link" href="#producto">Volver al producto <span aria-hidden="true">↑</span></a>
      </div>
      <div className={`${layout.container} footer-legal`}>
        <p>Ficha preliminar: denominación, rótulo, precio y disponibilidad en validación. El carrito es una demostración y no confirma una compra.</p>
        <span>© Mejor Vida Ceto</span>
      </div>
    </footer>
  );
}
