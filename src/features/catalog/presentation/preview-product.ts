export const previewProduct = {
  id: "ceto-bhb-preview",
  slug: "ceto-bhb",
  name: "Ceto BHB",
  detail: "Ficha preliminar. Presentación, precio y disponibilidad en validación.",
} as const;

export const previewCartItem = {
  id: previewProduct.id,
  name: previewProduct.name,
  detail: previewProduct.detail,
  unitPrice: null,
  quantity: 1,
  maxQuantity: 99,
} as const;

export const requiredSupplementNotice =
  "SUPLEMENTA DIETAS INSUFICIENTES. CONSULTE A SU MÉDICO Y/O FARMACÉUTICO";
