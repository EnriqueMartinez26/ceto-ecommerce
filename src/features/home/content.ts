export const offers = [
  { units: 1, label: "1 mes", note: "1 frasco · 60 cápsulas" },
  { units: 2, label: "2 meses", note: "2 frascos" },
  { units: 3, label: "3 meses", note: "3 frascos" },
] as const;

export const ingredients = [
  "Ceto BHB",
  "Cafeína natural",
  "Té verde",
  "L-Carnitina",
  "Guaraná",
  "Picolinato de cromo",
  "Complejo B",
] as const;

export const planItems = [
  {
    number: "01",
    title: "Ceto BHB",
    description: "Un frasco de 60 cápsulas para acompañar tu rutina.",
  },
  {
    number: "02",
    title: "Guía de alimentación",
    description: "Un plan digital bajo en carbohidratos para organizar tus comidas.",
  },
  {
    number: "03",
    title: "Rutina en casa",
    description: "Una propuesta práctica para sumar movimiento a tu semana.",
  },
  {
    number: "04",
    title: "Acompañamiento",
    description: "Seguimiento semanal por WhatsApp para resolver dudas del plan.",
  },
] as const;

export const heroLabels = ["La propuesta", "Qué incluye", "Elegí tu combo"] as const;

export const siteLinks = [
  { id: "inicio", label: "Inicio" },
  { id: "plan", label: "El plan" },
  { id: "formula", label: "Ingredientes" },
  { id: "preguntas", label: "Preguntas" },
] as const;
