import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import ts from "typescript";

async function transpileTypeScript(path, imports = {}) {
  const source = await readFile(new URL(path, import.meta.url), "utf8");
  const { outputText: originalOutput, diagnostics } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
    reportDiagnostics: true,
  });
  assert.equal(diagnostics?.length ?? 0, 0, `${path} should transpile without diagnostics.`);
  const outputText = Object.entries(imports).reduce(
    (sourceText, [specifier, replacement]) => sourceText.replaceAll(`from "${specifier}"`, `from "${replacement}"`),
    originalOutput,
  );
  return `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`;
}

const cartUrl = await transpileTypeScript("../../src/shared/cart/cart-model.ts");
const cart = await import(cartUrl);
const storageUrl = await transpileTypeScript("../../src/shared/cart/cart-storage.ts", { "./cart-model": cartUrl });
const storage = await import(storageUrl);

const product = {
  id: "ceto-bhb-preview",
  name: "Ceto BHB",
  detail: "Ficha preliminar. Presentación, precio y disponibilidad en validación.",
  unitPrice: null,
  maxQuantity: 99,
};

test("keeps unconfirmed prices pending and never derives a subtotal", () => {
  const added = cart.addCartItem([], product);
  assert.equal(added.changed, true);
  assert.deepEqual(cart.summarizeCart(added.lines), {
    lineCount: 1,
    itemCount: 1,
    pricing: "pending",
    subtotal: null,
  });
});

test("merges repeated additions, updates quantity and removes the selected product", () => {
  const first = cart.addCartItem([], product).lines;
  const merged = cart.addCartItem(first, { ...product, quantity: 2 }).lines;
  assert.equal(merged[0].quantity, 3);
  const adjusted = cart.setCartItemQuantity(merged, product.id, 2).lines;
  assert.equal(adjusted[0].quantity, 2);
  assert.deepEqual(cart.removeCartItem(adjusted, product.id).lines, []);
});

test("enforces bounded lines and quantities and calculates a confirmed subtotal", () => {
  const full = Array.from({ length: cart.CART_MAX_LINES }, (_, index) => ({ ...product, id: `product-${index}`, quantity: 1 }));
  assert.equal(cart.addCartItem(full, { ...product, id: "extra-product" }).changed, false);
  assert.equal(cart.setCartItemQuantity(full, "product-0", cart.CART_MAX_QUANTITY + 1).changed, false);

  const priced = cart.addCartItem([], { ...product, unitPrice: { currency: "ARS", amountMinor: 1250 }, quantity: 2 });
  assert.deepEqual(cart.summarizeCart(priced.lines), {
    lineCount: 1,
    itemCount: 2,
    pricing: "confirmed",
    subtotal: { currency: "ARS", amountMinor: 2500 },
  });
});

test("respects the per-product quantity limit supplied by the catalog", () => {
  const limitedItem = { ...product, maxQuantity: 3 };
  const added = cart.addCartItem([], limitedItem);
  assert.equal(cart.addCartItem(added.lines, { ...limitedItem, quantity: 2 }).lines[0].quantity, 3);
  assert.equal(cart.setCartItemQuantity(added.lines, product.id, 4).changed, false);

  const changedLimit = cart.addCartItem([{ ...added.lines[0], quantity: 3 }], { ...limitedItem, maxQuantity: 2 });
  assert.equal(changedLimit.lines[0].maxQuantity, 2);
  assert.equal(changedLimit.lines[0].quantity, 2);
});

test("clears the cart and leaves totals pending when integer arithmetic would overflow", () => {
  const populated = cart.addCartItem([], product).lines;
  assert.equal(cart.clearCart(populated).lines.length, 0);
  assert.equal(cart.clearCart([]).changed, false);

  const oversized = cart.addCartItem([], {
    ...product,
    unitPrice: { currency: "ARS", amountMinor: Number.MAX_SAFE_INTEGER },
    quantity: 2,
  });
  assert.equal(cart.summarizeCart(oversized.lines).pricing, "pending");
  assert.equal(cart.summarizeCart(oversized.lines).subtotal, null);
});

test("uses only the current versioned storage envelope and tolerates unavailable storage", () => {
  const stored = JSON.stringify({ version: 2, items: [{ ...product, quantity: 1 }] });
  assert.equal(storage.parseCartStorageValue(stored).length, 1);
  const legacyLine = { ...product, quantity: 1 };
  delete legacyLine.maxQuantity;
  const legacy = JSON.stringify({ version: 1, items: [legacyLine] });
  assert.equal(storage.parseCartStorageValue(legacy)[0].maxQuantity, 99);
  assert.deepEqual(storage.parseCartStorageValue(JSON.stringify({ version: 1, items: [{ ...product, quantity: 1, unitPrice: undefined }] })), []);
  assert.deepEqual(storage.parseCartStorageValue(JSON.stringify({ version: 0, items: [{ ...product, quantity: 1 }] })), []);
  assert.deepEqual(storage.parseCartStorageValue("not json"), []);
  assert.equal(storage.readCart({ getItem: () => { throw new Error("blocked"); }, setItem() {} }).length, 0);
  assert.equal(storage.writeCart({ getItem: () => null, setItem() { throw new Error("blocked"); } }, []), false);
});
