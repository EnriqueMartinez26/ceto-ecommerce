import Link from "next/link";
import styles from "./not-found.module.css";
import layout from "@/shared/ui/layout.module.css";

export default function NotFound() {
  return (
    <>
      <a className="skip-link" href="#main-content">Saltar al contenido principal</a>
      <main id="main-content" tabIndex={-1} className={`${styles.page} ${layout.container}`}>
        <div className="not-found-page__code" aria-hidden="true">404<span>↗</span></div>
        <div className="not-found-page__content">
          <p className={styles.eyebrow}><span className={styles.eyebrowLine} /> ERROR / RUTA NO DISPONIBLE</p>
          <h1>Esta página<br /><em>no está acá.</em></h1>
          <p className={styles.description}>La dirección que abriste no existe. Volvé al inicio para conocer el plan y las opciones disponibles.</p>
          <Link className={`${styles.button} ${styles.buttonPrimary}`} href="/">Volver al inicio <span aria-hidden="true">↗</span></Link>
        </div>
      </main>
    </>
  );
}
