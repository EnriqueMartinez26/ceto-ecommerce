import "server-only";
import type { StoreProduct, StoreProductDetail, StoreProductPage } from "../model";
import { isCatalogSlug, parseProduct, parseProductDetail } from "./catalog-response";

export type CatalogResult<T> =
  | { status: "ready"; data: T }
  | { status: "not_configured" }
  | { status: "unavailable" }
  | { status: "not_found" };

export type CatalogPageResult<T> = Exclude<CatalogResult<T>, { status: "not_found" }>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function getStoreResponse(path: string): Promise<Response | null> {
  const baseUrl = process.env.CATALOG_API_URL;
  if (!baseUrl) return null;
  try {
    const url = new URL(path, baseUrl);
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    return await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(5000) });
  } catch {
    return null;
  }
}

export async function getCatalog(page = 0, size = 20): Promise<CatalogPageResult<StoreProductPage>> {
  if (!process.env.CATALOG_API_URL?.trim()) return { status: "not_configured" };
  if (!Number.isSafeInteger(page) || page < 0 || page > 1_000_000 ||
    !Number.isSafeInteger(size) || size < 1 || size > 100) return { status: "unavailable" };

  const query = new URLSearchParams({ page: String(page), size: String(size) });
  const response = await getStoreResponse(`/api/v1/products?${query.toString()}`);
  if (!response?.ok) return { status: "unavailable" };
  try {
    const body: unknown = await response.json();
    if (!isRecord(body) || !Array.isArray(body.items) ||
      !Number.isSafeInteger(body.page) || body.page !== page ||
      !Number.isSafeInteger(body.size) || body.size !== size ||
      typeof body.hasNext !== "boolean" || body.items.length > size ||
      (body.hasNext && body.items.length !== size)) return { status: "unavailable" };
    const products = body.items.map(parseProduct);
    if (products.some((product) => product === null)) return { status: "unavailable" };
    return { status: "ready", data: { items: products as StoreProduct[], page, size, hasNext: body.hasNext } };
  } catch {
    return { status: "unavailable" };
  }
}

export async function getProduct(slug: string): Promise<CatalogResult<StoreProductDetail>> {
  if (!isCatalogSlug(slug)) return { status: "not_found" };
  if (!process.env.CATALOG_API_URL?.trim()) return { status: "not_configured" };
  const response = await getStoreResponse(`/api/v1/products/${encodeURIComponent(slug)}`);
  if (response?.status === 404) return { status: "not_found" };
  if (!response?.ok) return { status: "unavailable" };
  try {
    const body: unknown = await response.json();
    const product = parseProductDetail(body);
    return product && product.slug === slug ? { status: "ready", data: product } : { status: "unavailable" };
  } catch {
    return { status: "unavailable" };
  }
}
