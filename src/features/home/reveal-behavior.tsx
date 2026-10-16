"use client";

import { useEffect, type ReactNode } from "react";

export function RevealBehavior({ children }: { children: ReactNode }) {
  useEffect(() => {
    if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    const sections = document.querySelectorAll<HTMLElement>("[data-reveal]");
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-revealed");
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.08 });

    sections.forEach((section) => {
      observer.observe(section);
      section.classList.add("reveal-ready");
    });

    return () => observer.disconnect();
  }, []);

  return children;
}
