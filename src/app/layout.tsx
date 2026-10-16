import type { Metadata, Viewport } from "next";
import { Inter, Roboto_Condensed } from "next/font/google";
import { CartProvider } from "@/shared/cart";
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
  title: "Mejor Vida Ceto | Información en revisión",
  description:
    "Conocé la propuesta de Mejor Vida Ceto. La información del producto y las condiciones comerciales están en revisión.",
  applicationName: "Mejor Vida Ceto",
  openGraph: {
    title: "Mejor Vida Ceto | Información en revisión",
    description:
      "Conocé la propuesta de Mejor Vida Ceto. La información del producto y las condiciones comerciales están en revisión.",
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
      <body>
        <CartProvider>{children}</CartProvider>
      </body>
    </html>
  );
}
