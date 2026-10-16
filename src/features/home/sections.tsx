import { ingredients, planItems } from "./content";
import layout from "@/shared/ui/layout.module.css";

export function OfferBand() {
  return (
    <section className="offer-band" aria-label="Resumen de la propuesta">
      <ul className={`${layout.container} offer-band__inner`} aria-label="Qué incluye el plan">
        {planItems.map((item) => (
          <li className="benefit-item" key={item.number}>
            <span className="benefit-item__number">{item.number}</span>
            <span className="benefit-item__title">{item.title}</span>
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
        <p className="eyebrow"><span className="eyebrow__line" /> EL ECOSISTEMA MEJOR VIDA</p>
        <h2>Todo lo que necesitás,<br /><em>en un mismo plan.</em></h2>
        <p className="section-heading__text">Cuatro recursos para acompañar tu proceso, con información clara y soporte cercano.</p>
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

export function FormulaSection() {
  return (
    <section className="formula-section" id="formula" data-reveal>
      <div className={`${layout.container} formula-layout`}>
        <div className="section-heading section-heading--light">
        <span className="section-index" aria-hidden="true">04 / 05</span>
          <p className="eyebrow"><span className="eyebrow__line" /> INGREDIENTES INFORMADOS</p>
          <h2>Una fórmula.<br /><em>Siete componentes.</em></h2>
          <p className="section-heading__text">Estos son los ingredientes informados para el producto. Consultá el rótulo del envase para conocer cantidades y modo de uso.</p>
        </div>
        <div className="ingredient-grid">
          {ingredients.map((ingredient, index) => (
            <div className="ingredient-card" key={ingredient}>
              <span>0{index + 1}</span>
              <b>{ingredient}</b>
              <span className="ingredient-card__spark" aria-hidden="true">✳</span>
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
        <p className="eyebrow"><span className="eyebrow__line" /> ANTES DE EMPEZAR</p>
        <h2>Preguntas <em>frecuentes.</em></h2>
      </div>
      <div className="faq-list">
        <details>
          <summary><span className="faq-index" aria-hidden="true">01</span>¿Qué incluye el plan?</summary>
          <p>Incluye Ceto BHB, una guía digital de alimentación, una rutina para hacer en casa y seguimiento semanal por WhatsApp.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">02</span>¿Cómo se toma Ceto BHB?</summary>
          <p>Seguí las indicaciones del rótulo del envase. Si tenés dudas sobre si es adecuado para vos, consultá con un profesional de salud.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">03</span>¿Es apto para personas celíacas?</summary>
          <p>La aptitud debe confirmarse con el rótulo vigente y la información oficial del fabricante. Esa validación está pendiente antes de publicar una respuesta definitiva.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">04</span>¿Cuándo se despachan los pedidos?</summary>
          <p>La cobertura, el operador y los plazos de entrega están en proceso de confirmación. Publicaremos esa información antes de habilitar la compra.</p>
        </details>
        <details>
          <summary><span className="faq-index" aria-hidden="true">05</span>¿Los resultados son iguales para todas?</summary>
          <p>No se prometen resultados específicos. La experiencia puede variar; el suplemento no reemplaza una alimentación equilibrada ni el asesoramiento profesional.</p>
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
        <p>Un paso a la vez.<br /><em>Con un plan que te acompaña.</em></p>
        <a className="footer-link" href="#combos">Volver a los combos <span aria-hidden="true">↑</span></a>
      </div>
      <div className={`${layout.container} footer-legal`}>
        <p>Suplemento dietario. Leé el rótulo y seguí las indicaciones de uso. Consultá con un profesional de salud antes de comenzar, especialmente si estás embarazada, amamantando, tomás medicación o tenés una condición preexistente.</p>
        <span>© Mejor Vida Ceto</span>
      </div>
    </footer>
  );
}
