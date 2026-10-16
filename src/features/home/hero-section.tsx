import layout from "@/shared/ui/layout.module.css";

export function HeroSection() {
  return (
    <section className={`hero ${layout.container}`} id="inicio" aria-labelledby="hero-title">
      <div className="hero-grid">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow__line" /> MEJOR VIDA, A TU RITMO</p>
          <h1 id="hero-title">Un plan para acompañar tus hábitos, <em>paso a paso.</em></h1>
          <p className="hero-copy__text">
            Ceto BHB, una guía de alimentación, una rutina en casa y seguimiento semanal en una propuesta clara y cercana.
          </p>
          <div className="hero-actions">
            <a className="button button--primary" href="#combos">Conocer opciones <span aria-hidden="true">↓</span></a>
            <a className="text-link" href="#plan">Qué incluye el plan <span aria-hidden="true">↗</span></a>
          </div>
          <ul className="hero-proof" aria-label="Información del plan">
            <li><strong>60</strong><span>cápsulas</span></li>
            <li><strong>4</strong><span>recursos</span></li>
            <li><strong>Semanal</strong><span>seguimiento</span></li>
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
              <small>60 CÁPSULAS</small>
            </div>
            <span className="hero-visual__seal">UN PASO<br />A LA VEZ</span>
          </div>
          <figcaption>Composición gráfica de marca. La fotografía oficial del envase está pendiente.</figcaption>
        </figure>
      </div>
      <p className="hero-note">Una propuesta integral, con información clara desde el primer paso.</p>
    </section>
  );
}
