import styles from "./home.module.css";
import { HeroSection } from "./hero-section";
import { RevealBehavior } from "./reveal-behavior";
import { SiteHeader } from "./site-header";
import { ProductPreviewSection } from "./product-preview-section";
import {
  FaqSection,
  OfferBand,
  PlanSection,
  ProductInformationSection,
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
            <ProductPreviewSection />
            <PlanSection />
            <ProductInformationSection />
            <FaqSection />
          </main>

          <SiteFooter />
        </>
      </RevealBehavior>
    </div>
  );
}
