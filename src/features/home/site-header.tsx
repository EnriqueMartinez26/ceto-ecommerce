"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { siteLinks } from "./content";

export function SiteHeader() {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [activeSection, setActiveSection] = useState("inicio");

  useEffect(() => {
    const updateSection = () => {
      const current = [...siteLinks].reverse().find(({ id }) => {
        const section = document.getElementById(id);
        return section && section.getBoundingClientRect().top <= 160;
      });
      setActiveSection(current?.id ?? "inicio");
    };

    const sections = siteLinks
      .map(({ id }) => document.getElementById(id))
      .filter((section): section is HTMLElement => section instanceof HTMLElement);

    if (typeof window.IntersectionObserver !== "function") {
      updateSection();
      window.addEventListener("scroll", updateSection, { passive: true });
      window.addEventListener("resize", updateSection);
      return () => {
        window.removeEventListener("scroll", updateSection);
        window.removeEventListener("resize", updateSection);
      };
    }

    let observer: IntersectionObserver | undefined;
    const observeSections = () => {
      observer?.disconnect();
      const bottomInset = Math.max(0, window.innerHeight - 161);
      observer = new IntersectionObserver(updateSection, {
        rootMargin: `-160px 0px -${bottomInset}px 0px`,
        threshold: 0,
      });
      sections.forEach((section) => observer?.observe(section));
    };

    updateSection();
    observeSections();
    window.addEventListener("resize", observeSections);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", observeSections);
    };
  }, []);

  useEffect(() => () => document.body.classList.remove("mobile-menu-open"), []);

  useEffect(() => {
    const query = window.matchMedia("(max-width: 767px)");
    const closeOnDesktop = () => {
      if (!query.matches) {
        dialogRef.current?.close();
        document.body.classList.remove("mobile-menu-open");
      }
    };
    query.addEventListener("change", closeOnDesktop);
    return () => query.removeEventListener("change", closeOnDesktop);
  }, []);

  function openMenu() {
    dialogRef.current?.showModal();
    document.body.classList.add("mobile-menu-open");
  }

  function closeMenu() {
    dialogRef.current?.close();
    document.body.classList.remove("mobile-menu-open");
  }

  return (
    <>
      <header className="site-header">
        <button className="mobile-menu-trigger" type="button" onClick={openMenu} aria-label="Abrir menú" aria-haspopup="dialog">☰</button>
        <a className="wordmark" href="#inicio" aria-label="Mejor Vida Ceto, inicio">
          <span className="wordmark__text">MEJOR VIDA <b>CETO</b></span>
        </a>
        <nav className="desktop-nav" aria-label="Navegación principal">
          {siteLinks.slice(1).map(({ id, label }) => (
            <a key={id} href={`#${id}`} aria-current={activeSection === id ? "location" : undefined}>{label}</a>
          ))}
          <Link className="desktop-nav__catalog" href="/productos">Productos</Link>
        </nav>
        <a className="header-cta" href="#combos">Ver combos <span aria-hidden="true">↓</span></a>
      </header>
      <dialog
        className="mobile-menu"
        ref={dialogRef}
        aria-label="Menú de navegación"
        onClose={() => document.body.classList.remove("mobile-menu-open")}
      >
        <div className="mobile-menu__head">
          <span>MEJOR VIDA / NAVEGACIÓN</span>
          <button type="button" onClick={closeMenu} aria-label="Cerrar menú">×</button>
        </div>
        <nav aria-label="Navegación móvil">
          {siteLinks.map(({ id, label }, index) => (
            <a key={id} href={`#${id}`} onClick={closeMenu} aria-current={activeSection === id ? "location" : undefined}>
              <span>{String(index + 1).padStart(2, "0")}</span>{label}<span aria-hidden="true">↗</span>
            </a>
          ))}
          <Link className="mobile-menu__catalog" href="/productos" onClick={closeMenu}>
            <span>05</span>Productos<span aria-hidden="true">↗</span>
          </Link>
        </nav>
        <p>Conocé el plan y las opciones disponibles. La compra online se habilitará al confirmar los datos comerciales.</p>
      </dialog>
    </>
  );
}
