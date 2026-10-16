import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import ts from "typescript";

const parserPath = new URL("../../src/features/catalog/server/catalog-response.ts", import.meta.url);
const source = await readFile(parserPath, "utf8");
const { outputText, diagnostics } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  reportDiagnostics: true,
});
assert.equal(diagnostics?.length ?? 0, 0, "Catalog response parser should transpile without diagnostics.");
const parserUrl = `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`;
const { isCatalogSlug, parseProduct, parseProductDetail } = await import(parserUrl);

const baseProduct = {
  id: "a5b4ca02-10e1-4c06-9cf7-89b600b5901a",
  slug: "producto-base",
  sku: "prod_01.a",
  name: "Producto base",
  kind: "BASE",
  unitRatio: 1,
  priceArs: "1200.00",
  compareAtPriceArs: null,
  availability: "IN_STOCK",
  maxQuantity: 5,
};

test("preserves known availability and tolerates future string values", () => {
  assert.equal(parseProduct(baseProduct)?.availability, "IN_STOCK");
  assert.equal(parseProduct({ ...baseProduct, availability: "COMING_SOON" })?.availability, "UNKNOWN");
});

test("accepts valid base and combo invariants and rejects inconsistent products", () => {
  assert.equal(parseProduct(baseProduct)?.kind, "BASE");
  assert.equal(parseProduct({ ...baseProduct, kind: "COMBO", unitRatio: 2 })?.comboUnits, 2);
  assert.equal(parseProduct({ ...baseProduct, kind: "BASE", unitRatio: 2 }), null);
  assert.equal(parseProduct({ ...baseProduct, kind: "COMBO", unitRatio: 1 }), null);
  assert.equal(parseProduct({ ...baseProduct, priceArs: "12.0" }), null);
  assert.equal(parseProduct({ ...baseProduct, priceArs: "0.00" }), null);
  assert.equal(parseProduct({ ...baseProduct, priceArs: "90071992547409.92" }), null);
  assert.equal(parseProduct({ ...baseProduct, compareAtPriceArs: "1200.00" }), null);
  assert.equal(parseProduct({ ...baseProduct, sku: "" }), null);
});

test("validates product slugs consistently for requests and responses", () => {
  assert.equal(isCatalogSlug("producto-base"), true);
  assert.equal(isCatalogSlug("Mayuscula"), false);
  assert.equal(isCatalogSlug("-prefijo"), false);
  assert.equal(isCatalogSlug("a".repeat(161)), false);
  assert.equal(parseProduct({ ...baseProduct, slug: "-invalido" }), null);
});

test("accepts nullable detail fields and rejects malformed detail payloads", () => {
  const detail = { ...baseProduct, presentation: null, ingredients: "Componente", usage: "Uso" };
  const parsedBase = parseProduct(baseProduct);
  assert.deepEqual(parseProductDetail(detail), {
    ...parsedBase,
    presentation: null,
    ingredients: "Componente",
    usage: "Uso",
  });
  assert.equal(parseProductDetail({ ...detail, usage: 12 }), null);
  assert.equal(parseProductDetail({ ...baseProduct, presentation: null }), null);
});
