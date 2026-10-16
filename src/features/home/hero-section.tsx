import layout from "@/shared/ui/layout.module.css";

export function HeroSection() {
  return (
    <section className={`hero ${layout.container}`} id="inicio" aria-labelledby="hero-title">
      <div className="hero-grid">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow__line" /> MEJOR VIDA, A TU RITMO</p>
          <h1 id="hero-title">Una propuesta clara, <em>paso a paso.</em></h1>
          <p className="hero-copy__text">
            El material recibido menciona Ceto BHB y otros recursos. Estamos validando la información y las condiciones antes de publicar una oferta.
          </p>
          <div className="hero-actions">
            <a className="button button--primary" href="#producto">Ver ficha preliminar <span aria-hidden="true">↓</span></a>
            <a className="text-link" href="#plan">Ver propuesta mencionada <span aria-hidden="true">↗</span></a>
          </div>
          <ul className="hero-proof" aria-label="Estado de la información comercial">
            <li><strong>Ficha</strong><span>en revisión</span></li>
            <li><strong>Precio</strong><span>a confirmar</span></li>
            <li><strong>Compra</strong><span>no disponible</span></li>
          </ul>
        </div>

        <figure className="hero-visual">
          <div className="hero-visual__art" aria-hidden="true">
            <span className="hero-visual__sun" />
            <span className="hero-visual__orbit hero-visual__orbit--one" />
            <span className="hero-visual__orbit hero-visual__orbit--two" />
            <div className="hero-visual__poster">
              <span>MEJOR VIDA <b>CETO</b></span>
              <strong>CETO<br />BHB</strong>
              <small>DATOS EN VALIDACIÓN</small>
            </div>
            <span className="hero-visual__seal">UN PASO<br />A LA VEZ</span>
          </div>
          <figcaption>Composición gráfica conceptual. No representa el rótulo ni el envase oficial.</figcaption>
        </figure>
      </div>
      <p className="hero-note">La vista previa del carrito no procesa compras ni reserva productos.</p>
    </section>
  );
}
