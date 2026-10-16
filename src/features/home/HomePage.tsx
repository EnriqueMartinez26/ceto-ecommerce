import styles from "./home.module.css";
import { ComboSelector } from "./combo-selector";
import { HeroSection } from "./hero-section";
import { RevealBehavior } from "./reveal-behavior";
import { SiteHeader } from "./site-header";
import {
  FaqSection,
  FormulaSection,
  OfferBand,
  PlanSection,
  SiteFooter,
} from "./sections";

export function HomePage() {
  return (
    <div className={styles.home}>
      <RevealBehavior>
        <>
          <a className="skip-link" href="#main-content">Saltar al contenido principal</a>
          <SiteHeader />

          <main id="main-content" tabIndex={-1}>
            <HeroSection />
            <OfferBand />
            <ComboSelector />
            <PlanSection />
            <FormulaSection />
            <FaqSection />
          </main>

          <SiteFooter />
        </>
      </RevealBehavior>
    </div>
  );
}
