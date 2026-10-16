export const productValidationItems = [
  { number: "01", label: "Denominación oficial: en validación" },
  { number: "02", label: "Presentación: pendiente de confirmar" },
  { number: "03", label: "Composición y uso: pendientes de validar" },
  { number: "04", label: "Precio y disponibilidad: pendientes" },
] as const;

export const planItems = [
  {
    number: "01",
    title: "Ceto BHB",
    description: "Nombre comunicado en el material recibido; denominación y ficha oficial pendientes de validación.",
  },
  {
    number: "02",
    title: "Guía de alimentación",
    description: "El material la menciona; contenido, alcance y condiciones de entrega pendientes de confirmación.",
  },
  {
    number: "03",
    title: "Actividad en casa",
    description: "La publicación la menciona; alcance y condiciones pendientes de confirmación.",
  },
  {
    number: "04",
    title: "Acompañamiento",
    description: "El material lo menciona; canal, frecuencia y condiciones pendientes de confirmación.",
  },
] as const;

export const siteLinks = [
  { id: "inicio", label: "Inicio" },
  { id: "plan", label: "El plan" },
  { id: "producto", label: "Producto" },
  { id: "preguntas", label: "Preguntas" },
] as const;
