import type { Availability } from "@/features/catalog/model";

type ProductAvailabilityProps = {
  availability: Availability;
  availableClassName: string;
  unavailableClassName: string;
  as?: "p" | "span";
};

const availabilityLabels: Record<Availability, string> = {
  IN_STOCK: "Disponible",
  LOW_STOCK: "Pocas unidades",
  OUT_OF_STOCK: "Sin disponibilidad",
  UNKNOWN: "Disponibilidad a confirmar",
};

export function ProductAvailability({
  availability,
  availableClassName,
  unavailableClassName,
  as: Element = "span",
}: ProductAvailabilityProps) {
  const className = availability === "IN_STOCK" || availability === "LOW_STOCK"
    ? availableClassName
    : unavailableClassName;

  return <Element className={className}>{availabilityLabels[availability]}</Element>;
}
