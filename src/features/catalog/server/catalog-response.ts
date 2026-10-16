import type { Availability, StoreProduct, StoreProductDetail } from "../model";

const decimalPattern = /^\d+\.\d{2}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const slugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const skuPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/;

export function isCatalogSlug(value: string): boolean {
  return value.length <= 160 && slugPattern.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMoney(value: unknown): value is string {
  if (typeof value !== "string" || !decimalPattern.test(value)) return false;
  const minorUnits = Number(value.replace(".", ""));
  return Number.isSafeInteger(minorUnits) && minorUnits > 0;
}

function parseAvailability(value: unknown): Availability | null {
  if (typeof value !== "string") return null;
  if (value === "IN_STOCK" || value === "LOW_STOCK" || value === "OUT_OF_STOCK") return value;
  return "UNKNOWN";
}

export function parseProduct(value: unknown): StoreProduct | null {
  if (!isRecord(value)) return null;
  const { id, slug, sku, name, kind, unitRatio, priceArs, compareAtPriceArs, maxQuantity } = value;
  const availability = parseAvailability(value.availability);
  if (
    typeof id !== "string" || !uuidPattern.test(id) ||
    typeof slug !== "string" || !isCatalogSlug(slug) ||
    typeof sku !== "string" || !skuPattern.test(sku) ||
    typeof name !== "string" || !name.trim() ||
    (kind !== "BASE" && kind !== "COMBO") ||
    typeof unitRatio !== "number" || !Number.isSafeInteger(unitRatio) || unitRatio < 1 ||
    !isMoney(priceArs) ||
    (compareAtPriceArs !== null && !isMoney(compareAtPriceArs)) ||
    availability === null ||
    typeof maxQuantity !== "number" || !Number.isSafeInteger(maxQuantity) || maxQuantity < 0
  ) return null;
  if (compareAtPriceArs !== null && Number(compareAtPriceArs.replace(".", "")) <= Number(priceArs.replace(".", ""))) return null;
  const product = {
    id, slug, sku, name, unitRatio, price: priceArs, compareAtPrice: compareAtPriceArs, availability, maxQuantity,
  } as const;
  if (kind === "BASE") return unitRatio === 1 ? { ...product, kind: "BASE", comboUnits: null } : null;
  if (unitRatio < 2) return null;
  return { ...product, kind: "COMBO", comboUnits: unitRatio };
}

export function parseProductDetail(value: unknown): StoreProductDetail | null {
  const product = parseProduct(value);
  if (!product || !isRecord(value)) return null;
  const { presentation, ingredients, usage } = value;
  if ((presentation !== null && typeof presentation !== "string") ||
    (ingredients !== null && typeof ingredients !== "string") ||
    (usage !== null && typeof usage !== "string")) return null;
  return { ...product, presentation, ingredients, usage };
}
