import type { Availability } from "./availability";

type StoreProductFields = {
  id: string;
  slug: string;
  sku: string;
  name: string;
  unitRatio: number;
  price: string;
  compareAtPrice: string | null;
  availability: Availability;
  maxQuantity: number;
};

export type StoreProduct = StoreProductFields & (
  | { kind: "BASE"; comboUnits: null }
  | { kind: "COMBO"; comboUnits: number }
);

export type StoreProductDetail = StoreProduct & {
  presentation: string | null;
  ingredients: string | null;
  usage: string | null;
};

export type StoreProductPage = {
  items: StoreProduct[];
  page: number;
  size: number;
  hasNext: boolean;
};
