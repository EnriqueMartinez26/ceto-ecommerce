import type { Metadata, Viewport } from "next";
import { Inter, Roboto_Condensed } from "next/font/google";
import "./globals.css";

const uiFont = Roboto_Condensed({
  subsets: ["latin"],
  variable: "--font-roboto-condensed",
});

const bodyFont = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
});

export const metadata: Metadata = {
  title: "Mejor Vida Ceto | Tu plan, con acompañamiento",
  description:
    "Ceto BHB, guía de alimentación, rutina en casa y seguimiento por WhatsApp en una propuesta integral.",
  applicationName: "Mejor Vida Ceto",
  openGraph: {
    title: "Mejor Vida Ceto | Tu plan, con acompañamiento",
    description:
      "Conocé el plan Mejor Vida Ceto: producto, recursos digitales y seguimiento cercano.",
    locale: "es_AR",
    type: "website",
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#fbf8f1",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="es-AR" className={`${uiFont.variable} ${bodyFont.variable}`}>
      <body>{children}</body>
    </html>
  );
}
