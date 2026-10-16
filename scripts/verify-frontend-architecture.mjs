import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import postcss from "postcss";
import ts from "typescript";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(root, "src");
const catalogAdapter = "src/features/catalog/server/catalog-api.ts";
const reviewedClientModules = new Set([
  "src/features/home/combo-selector.tsx",
  "src/features/home/reveal-behavior.tsx",
  "src/features/home/site-header.tsx",
]);
const globalDeclarations = new Map([
  [":root", new Set(["color-scheme", "--*"])],
  ["*", new Set(["box-sizing"])],
  ["html", new Set(["scroll-behavior"])],
  ["body", new Set(["margin", "background", "color", "font-family", "-webkit-font-smoothing"])],
  ["body.mobile-menu-open", new Set(["overflow"])],
  ["a", new Set(["color", "text-decoration"])],
  ["button", new Set(["color", "font"])],
  ["::selection", new Set(["background", "color"])],
  [".skip-link", new Set(["position", "top", "left", "z-index", "min-height", "display", "align-items", "padding", "transform", "border", "border-radius", "background", "box-shadow", "color", "font-family", "font-weight", "text-transform", "transition"])],
  [".skip-link:focus", new Set(["transform"])],
  [".skip-link:focus-visible", new Set(["outline", "outline-offset"])],
  [":focus-visible", new Set(["outline", "outline-offset"])],
  ["section[id]", new Set(["scroll-margin-top"])],
  ["footer[id]", new Set(["scroll-margin-top"])],
  ["*::before", new Set(["scroll-behavior", "animation-duration", "animation-iteration-count", "transition-duration"])],
  ["*::after", new Set(["scroll-behavior", "animation-duration", "animation-iteration-count", "transition-duration"])],
]);

function normalizeRelative(absolutePath) {
  return path.relative(root, absolutePath).split(path.sep).join("/");
}

function resolveImport(sourcePath, specifier) {
  if (specifier.startsWith("@/")) return path.join(sourceRoot, specifier.slice(2));
  if (specifier.startsWith(".")) {
    return path.resolve(root, path.dirname(sourcePath), specifier);
  }
  return null;
}

function resolveSourceModule(sourcePath, specifier, sourceFiles) {
  const unresolved = resolveImport(sourcePath, specifier);
  if (!unresolved) return null;
  const candidates = [
    unresolved,
    ...[".ts", ".tsx", ".js", ".jsx", ".mts", ".mjs"].map((extension) => `${unresolved}${extension}`),
    ...["index.ts", "index.tsx", "index.js", "index.jsx"].map((name) => path.join(unresolved, name)),
  ];
  for (const candidate of candidates) {
    const relativePath = normalizeRelative(candidate);
    if (sourceFiles.has(relativePath)) return relativePath;
  }
  return null;
}

function ownerOf(sourcePath) {
  const parts = sourcePath.split("/");
  if (parts[0] === "src" && parts[1] === "features" && parts[2]) {
    return { kind: "feature", name: parts[2] };
  }
  if (parts[0] === "src" && parts[1] === "shared") return { kind: "shared" };
  if (parts[0] === "src" && parts[1] === "app") return { kind: "app" };
  return { kind: "outside" };
}

function moduleSpecifierOf(node) {
  if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
    return node.moduleSpecifier.text;
  }
  if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
    return node.arguments[0].text;
  }
  if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "require" && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
    return node.arguments[0].text;
  }
  if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference) && node.moduleReference.expression && ts.isStringLiteral(node.moduleReference.expression)) {
    return node.moduleReference.expression.text;
  }
  return null;
}

function isClientModule(sourceFile) {
  return sourceFile.statements.some((statement) =>
    ts.isExpressionStatement(statement) && ts.isStringLiteral(statement.expression) && statement.expression.text === "use client",
  );
}

function collectViolations(sourcePath, sourceText) {
  const extension = path.extname(sourcePath);
  const scriptKind = extension === ".tsx" ? ts.ScriptKind.TSX : extension === ".jsx" ? ts.ScriptKind.JSX : ts.ScriptKind.TS;
  const sourceFile = ts.createSourceFile(sourcePath, sourceText, ts.ScriptTarget.Latest, true, scriptKind);
  const violations = [];
  const owner = ownerOf(sourcePath);
  const client = isClientModule(sourceFile);
  const routePage = sourcePath.startsWith("src/app/") && /(?:^|\/)page\.(?:tsx?|jsx?)$/.test(sourcePath);

  const report = (code, detail) => violations.push({ code, detail });
  if (routePage && client) report("CLIENT_ROUTE", "App Router pages must remain Server Components.");
  if (routePage) {
    const validReexport = sourceFile.statements.length === 1 &&
      ts.isExportDeclaration(sourceFile.statements[0]) &&
      !sourceFile.statements[0].isTypeOnly &&
      sourceFile.statements[0].moduleSpecifier &&
      ts.isStringLiteral(sourceFile.statements[0].moduleSpecifier) &&
      /^@\/features\/[a-z0-9-]+$/.test(sourceFile.statements[0].moduleSpecifier.text) &&
      sourceFile.statements[0].exportClause &&
      ts.isNamedExports(sourceFile.statements[0].exportClause) &&
      sourceFile.statements[0].exportClause.elements.some(({ name }) => name.text === "default");
    if (!validReexport) report("ROUTE_FEATURE_ENTRY", "App Router pages must only re-export the default page from a public feature entry point.");
  }
  if (client && !reviewedClientModules.has(sourcePath)) {
    report("UNREVIEWED_CLIENT", "Add this interactive island to the reviewed client-module set after architectural review.");
  }

  function visit(node) {
    const specifier = moduleSpecifierOf(node);
    if (specifier) {
      const target = resolveImport(sourcePath, specifier);
      const targetPath = target ? normalizeRelative(target) : "";
      const targetOwner = target ? ownerOf(targetPath) : { kind: "outside" };

      if (target && !targetPath.startsWith("src/")) {
        report("INTERNAL_OUTSIDE_SOURCE", `Source modules may not import project files outside src (${specifier}).`);
      }
      if (owner.kind === "feature" && targetOwner.kind === "feature" && owner.name !== targetOwner.name) {
        report("CROSS_FEATURE_IMPORT", `${owner.name} cannot import feature ${targetOwner.name} (${specifier}).`);
      }
      if (targetPath.startsWith("src/") && owner.kind === "feature" && targetOwner.kind !== "feature" && targetOwner.kind !== "shared") {
        report("FEATURE_LAYER_DEPENDENCY", `Feature ${owner.name} cannot import app, legacy library, or infrastructure source (${specifier}).`);
      }
      if (targetPath.startsWith("src/") && owner.kind === "shared" && targetOwner.kind !== "shared") {
        report("SHARED_DEPENDENCY", `Shared code may only import shared source (${specifier}).`);
      }
      if (targetPath.startsWith("src/") && owner.kind === "app" && targetOwner.kind === "outside") {
        report("APP_LAYER_DEPENDENCY", `App routes cannot import legacy library or infrastructure source (${specifier}).`);
      }
      if (owner.kind === "app" && targetOwner.kind === "feature" && specifier !== `@/features/${targetOwner.name}`) {
        report("FEATURE_INTERNAL_IMPORT", `App routes must import the public feature entry point @/features/${targetOwner.name} (${specifier}).`);
      }
      if (client && (specifier === "server-only" || targetPath.includes("/server/"))) {
        report("SERVER_IMPORT_IN_CLIENT", `Client islands cannot import server-only modules (${specifier}).`);
      }
      if (specifier.endsWith(".css") && !specifier.endsWith(".module.css")) {
        const isRootGlobal = sourcePath === "src/app/layout.tsx" && specifier === "./globals.css";
        if (!isRootGlobal) report("NON_MODULE_STYLESHEET", `Only the root layout may import globals.css; component styles must use CSS Modules (${specifier}).`);
      }
      if (specifier.includes("/lib/catalog-api")) {
        report("LEGACY_CATALOG_ADAPTER", "Use the catalog feature's public entry point instead of the removed adapter path.");
      }
      if (sourcePath.startsWith("src/") && sourcePath !== catalogAdapter && /^(?:axios|ky|got|ofetch|node-fetch|cross-fetch|isomorphic-fetch|superagent|wretch|graphql-request|@apollo\/client|@urql\/core|request|needle|phin|undici|http|https|node:http|node:https)(?:\/|$)/.test(specifier)) {
        report("HTTP_CLIENT_OUTSIDE_ADAPTER", `HTTP clients must only be used by ${catalogAdapter} (${specifier}).`);
      }
    }

    if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "require" && !moduleSpecifierOf(node)) {
      report("DYNAMIC_COMMONJS_IMPORT", "Use a statically analyzable ES module import.");
    }
    if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword && !moduleSpecifierOf(node)) {
      report("DYNAMIC_IMPORT", "Use a statically analyzable import path.");
    }
    if (ts.isIdentifier(node) && ["fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource"].includes(node.text) && sourcePath !== catalogAdapter) {
      report("DIRECT_NETWORK_API", `Network APIs must stay behind the catalog server adapter (${node.text}).`);
    }
    if (ts.isElementAccessExpression(node) && node.argumentExpression && ts.isStringLiteralLike(node.argumentExpression) &&
      ["fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource"].includes(node.argumentExpression.text) &&
      sourcePath !== catalogAdapter) {
      report("DIRECT_NETWORK_API", "Computed network access must stay behind the catalog server adapter (" + node.argumentExpression.text + ").");
    }
    if (ts.isPropertyAccessExpression(node) && ts.isIdentifier(node.expression) && ["globalThis", "window", "self"].includes(node.expression.text) && node.name.text === "fetch" && sourcePath !== catalogAdapter) {
      report("DIRECT_NETWORK_API", "Network APIs must stay behind the catalog server adapter.");
    }
    if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && ["XMLHttpRequest", "WebSocket", "EventSource"].includes(node.expression.text) && sourcePath !== catalogAdapter) {
      report("DIRECT_NETWORK_API", `Network APIs must stay behind the catalog server adapter (${node.expression.text}).`);
    }
    if (ts.isStringLiteralLike(node) && node.text.includes("/api/v1/products") && sourcePath !== catalogAdapter) {
      report("DIRECT_CATALOG_ENDPOINT", "The public catalog endpoint may only be named in its server adapter.");
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
  return violations;
}

async function listSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return listSourceFiles(absolutePath);
    if (entry.isFile() && /\.(?:ts|tsx|js|jsx|mts|mjs)$/.test(entry.name)) return [absolutePath];
    return [];
  }));
  return nested.flat();
}

async function listCssFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const absolutePath = path.join(directory, entry.name);
    if (entry.isDirectory()) return listCssFiles(absolutePath);
    if (entry.isFile() && entry.name.endsWith(".css")) return [absolutePath];
    return [];
  }));
  return nested.flat();
}

function clientServerDependencyViolations(sourceFiles) {
  const violations = [];
  for (const [clientPath, clientText] of sourceFiles) {
    const clientFile = ts.createSourceFile(clientPath, clientText, ts.ScriptTarget.Latest, true, clientPath.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    if (!isClientModule(clientFile)) continue;

    const visited = new Set();
    const pending = [clientPath];
    while (pending.length > 0) {
      const currentPath = pending.pop();
      if (!currentPath || visited.has(currentPath)) continue;
      visited.add(currentPath);
      const sourceText = sourceFiles.get(currentPath);
      if (sourceText === undefined) continue;
      const currentFile = ts.createSourceFile(currentPath, sourceText, ts.ScriptTarget.Latest, true, currentPath.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
      const hasServerMarker = currentFile.statements.some((statement) => moduleSpecifierOf(statement) === "server-only");
      if (currentPath.includes("/server/") || hasServerMarker) {
        violations.push(`${clientPath}: Client module reaches server-only code through ${currentPath}.`);
        continue;
      }

      function visit(node) {
        const specifier = moduleSpecifierOf(node);
        if (specifier === "server-only") {
          violations.push(`${clientPath}: Client module reaches the server-only marker through ${currentPath}.`);
        } else if (specifier) {
          const resolved = resolveSourceModule(currentPath, specifier, sourceFiles);
          if (resolved) pending.push(resolved);
        }
        ts.forEachChild(node, visit);
      }
      visit(currentFile);
    }
  }
  return violations;
}

function globalStyleViolations(css, filePath = "src/app/globals.css") {
  const ast = postcss.parse(css, { from: filePath });
  const violations = [];
  ast.walkRules((rule) => {
    for (const selector of rule.selectors) {
      const allowed = globalDeclarations.get(selector.trim());
      if (!allowed) {
        violations.push(`Global selector ${JSON.stringify(selector.trim())} must move to an owned CSS Module.`);
        continue;
      }
      rule.walkDecls((declaration) => {
        const parent = rule.parent;
        const reducedMotionRule = parent?.type === "atrule" && parent.name === "media" && parent.params.trim() === "(prefers-reduced-motion: reduce)";
        const reducedMotionProperty = reducedMotionRule && ["*", "*::before", "*::after"].includes(selector.trim()) && ["scroll-behavior", "animation-duration", "animation-iteration-count", "transition-duration"].includes(declaration.prop);
        const propertyAllowed = allowed.has(declaration.prop) || (allowed.has("--*") && declaration.prop.startsWith("--")) || reducedMotionProperty;
        if (!propertyAllowed) violations.push(`Global selector ${JSON.stringify(selector.trim())} cannot declare ${declaration.prop}.`);
      });
    }
  });
  ast.walkAtRules((rule) => {
    const isTailwindImport = rule.name === "import" && rule.params.trim() === '"tailwindcss"';
    const isReducedMotion = rule.name === "media" && rule.params.trim() === "(prefers-reduced-motion: reduce)";
    if (!isTailwindImport && !isReducedMotion) {
      violations.push(`Global @${rule.name} ${rule.params.trim()} is outside the base stylesheet policy.`);
    }
  });
  return violations;
}

function hasLocalClassOrId(selector) {
  let bracketDepth = 0;
  let parenthesisDepth = 0;
  let quote = "";
  let escaped = false;
  for (let index = 0; index < selector.length; index += 1) {
    const character = selector[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (character === "\\") {
      escaped = true;
      continue;
    }
    if (quote) {
      if (character === quote) quote = "";
      continue;
    }
    if (character === "'" || character === '"') {
      quote = character;
      continue;
    }
    if (character === "/" && selector[index + 1] === "*") {
      const commentEnd = selector.indexOf("*/", index + 2);
      if (commentEnd === -1) return false;
      index = commentEnd + 1;
      continue;
    }
    if (bracketDepth === 0 && selector.startsWith(":global(", index)) {
      let depth = 1;
      let globalQuote = "";
      let globalEscaped = false;
      index += 7;
      while (++index < selector.length && depth > 0) {
        const globalCharacter = selector[index];
        if (globalEscaped) {
          globalEscaped = false;
        } else if (globalCharacter === "\\") {
          globalEscaped = true;
        } else if (globalQuote) {
          if (globalCharacter === globalQuote) globalQuote = "";
        } else if (globalCharacter === "'" || globalCharacter === '"') {
          globalQuote = globalCharacter;
        } else if (globalCharacter === "/" && selector[index + 1] === "*") {
          const commentEnd = selector.indexOf("*/", index + 2);
          if (commentEnd === -1) return false;
          index = commentEnd + 1;
        } else if (globalCharacter === "(") {
          depth += 1;
        } else if (globalCharacter === ")") {
          depth -= 1;
        }
      }
      if (depth > 0) return false;
      continue;
    }
    if (bracketDepth === 0 && selector.startsWith(":global", index)) {
      return false;
    }
    if (character === "[") {
      bracketDepth += 1;
      continue;
    }
    if (character === "]") {
      bracketDepth = Math.max(0, bracketDepth - 1);
      continue;
    }
    if (bracketDepth === 0 && character === "(") {
      parenthesisDepth += 1;
      continue;
    }
    if (bracketDepth === 0 && character === ")") {
      parenthesisDepth = Math.max(0, parenthesisDepth - 1);
      continue;
    }
    if (bracketDepth === 0 && parenthesisDepth === 0 && (character === "." || character === "#")) {
      const next = selector[index + 1];
      if (next && (/[A-Za-z_-]/.test(next) || next === "\\")) return true;
    }
  }
  return false;
}

function moduleStyleViolations(css, filePath) {
  const ast = postcss.parse(css, { from: filePath });
  const violations = [];
  ast.walkRules((rule) => {
    let parent = rule.parent;
    while (parent && parent.type !== "root") {
      if (parent.type === "atrule" && /^(?:-\w+-)?keyframes$/.test(parent.name)) return;
      parent = parent.parent;
    }
    for (const selector of rule.selectors) {
      if (!hasLocalClassOrId(selector)) {
        violations.push(`CSS Module selector ${JSON.stringify(selector)} must include a locally scoped class or id.`);
      }
    }
  });
  return violations;
}

async function verifyGlobalStyles() {
  const filePath = path.join(sourceRoot, "app", "globals.css");
  const css = await readFile(filePath, "utf8");
  return globalStyleViolations(css, normalizeRelative(filePath));
}

function runSelfTests() {
  const cases = [
    {
      name: "cross-feature dependency is rejected",
      path: "src/features/home/view.tsx",
      source: 'import { x } from "@/features/catalog/model";',
      code: "CROSS_FEATURE_IMPORT",
    },
    {
      name: "shared-to-feature dependency is rejected",
      path: "src/shared/ui/button.tsx",
      source: 'import { x } from "@/features/home";',
      code: "SHARED_DEPENDENCY",
    },
    {
      name: "feature-to-app dependency is rejected",
      path: "src/features/home/view.tsx",
      source: 'import { x } from "@/app/layout";',
      code: "FEATURE_LAYER_DEPENDENCY",
    },
    {
      name: "app import of a feature internal is rejected",
      path: "src/app/page.tsx",
      source: 'import { x } from "@/features/home/site-header";',
      code: "FEATURE_INTERNAL_IMPORT",
    },
    {
      name: "client route is rejected",
      path: "src/app/page.tsx",
      source: '"use client"; export default function Page() { return null; }',
      code: "CLIENT_ROUTE",
    },
    {
      name: "route-local page implementation is rejected",
      path: "src/app/productos/page.tsx",
      source: 'export default function Page() { return null; }',
      code: "ROUTE_FEATURE_ENTRY",
    },
    {
      name: "route reexport from legacy library is rejected",
      path: "src/app/productos/page.tsx",
      source: 'export { CatalogPage as default } from "@/lib/catalog-page";',
      code: "ROUTE_FEATURE_ENTRY",
    },
    {
      name: "unreviewed client island is rejected",
      path: "src/features/home/new-island.tsx",
      source: '"use client"; export function Island() { return null; }',
      code: "UNREVIEWED_CLIENT",
    },
    {
      name: "direct HTTP access is rejected",
      path: "src/features/catalog/presentation/view.tsx",
      source: 'export async function load() { return fetch("/api/v1/products"); }',
      code: "DIRECT_NETWORK_API",
    },
    {
      name: "alternative HTTP client is rejected outside the adapter",
      path: "src/features/home/view.tsx",
      source: 'import { ofetch } from "ofetch"; export const load = () => ofetch("/");',
      code: "HTTP_CLIENT_OUTSIDE_ADAPTER",
    },
    {
      name: "core HTTP module is rejected outside the adapter",
      path: "src/features/home/view.tsx",
      source: 'import { get } from "https"; export const load = get;',
      code: "HTTP_CLIENT_OUTSIDE_ADAPTER",
    },
    {
      name: "computed fetch access is rejected outside the adapter",
      path: "src/features/home/view.tsx",
      source: 'export function load() { return window["fetch"]("/"); }',
      code: "DIRECT_NETWORK_API",
    },
    {
      name: "globalThis HTTP access is rejected",
      path: "src/features/home/site-header.tsx",
      source: '"use client"; export function load() { return globalThis.fetch("/"); }',
      code: "DIRECT_NETWORK_API",
    },
    {
      name: "beacon transport is rejected outside the adapter",
      path: "src/features/home/view.tsx",
      source: "export function track() { navigator.sendBeacon('/event'); }",
      code: "DIRECT_NETWORK_API",
    },
  ];
  for (const testCase of cases) {
    const codes = collectViolations(testCase.path, testCase.source).map(({ code }) => code);
    assert(codes.includes(testCase.code), `${testCase.name}: expected ${testCase.code}.`);
  }
  assert(globalStyleViolations(".hero { color: red; }").some((violation) => violation.includes(".hero")), "Global feature selector must be rejected.");
  assert(globalStyleViolations("a { color: red; background: hotpink; }").some((violation) => violation.includes("background")), "Global declarations must be restricted by selector.");
  assert(globalStyleViolations("@keyframes feature-enter { from { opacity: 0; } }").some((violation) => violation.includes("@keyframes")), "Global animation must be rejected.");
  assert(globalStyleViolations("@theme inline { --color: red; }").some((violation) => violation.includes("@theme")), "Unsupported global at-rules must be rejected.");
  assert(moduleStyleViolations(":global(.hero) { color: red; }").length > 0, "Unscoped :global selectors must be rejected.");
  assert(moduleStyleViolations("h1 { color: red; }").length > 0, "Unscoped element selectors must be rejected in CSS Modules.");
  assert(moduleStyleViolations('body[data-label=".scope"] { color: red; }').length > 0, "A class-like attribute value must not count as local CSS Module scope.");
  assert(moduleStyleViolations("@keyframes fade { from { opacity: 0; } to { opacity: 1; } }").length === 0, "Keyframe selectors do not need a CSS Module scope.");
  assert(moduleStyleViolations(".scope :global(.hero) { color: red; }").length === 0, "Locally scoped :global selectors must be accepted.");
  assert(moduleStyleViolations('.scope[data-label=".value"] { color: red; }').length === 0, "A local class stays scoped when attribute values contain class-like text.");
  assert(moduleStyleViolations(":global body .hero { color: red; }").length > 0, "Bare global selector mode cannot satisfy a local CSS Module scope.");
  assert(moduleStyleViolations("body/* .scope */ { color: red; }").length > 0, "A class-like CSS comment must not count as local CSS Module scope.");
  assert(moduleStyleViolations("body:has(.scope) { overflow: hidden; }").length > 0, "A local class nested inside a pseudo-class must not scope a global element selector.");
  const transitiveClientFiles = new Map([
    ["src/features/catalog/island.tsx", '"use client"; import { getCatalog } from "@/features/catalog";'],
    ["src/features/catalog/index.ts", 'export { getCatalog } from "./server/catalog-api";'],
    [catalogAdapter, 'import "server-only"; export async function getCatalog() { return fetch("/"); }'],
  ]);
  assert(clientServerDependencyViolations(transitiveClientFiles).some((violation) => violation.includes(catalogAdapter)), "Barrels may not hide a server-only dependency from a client island.");
  console.log("Architecture rule self-check passed (" + (cases.length + 14) + " policy checks).");
}

async function main() {
  if (process.argv.includes("--self-test")) runSelfTests();

  const files = (await listSourceFiles(sourceRoot)).map(normalizeRelative).sort();
  const sourceContents = new Map();
  const violations = [];
  for (const sourcePath of files) {
    const sourceText = await readFile(path.join(root, sourcePath), "utf8");
    sourceContents.set(sourcePath, sourceText);
    for (const violation of collectViolations(sourcePath, sourceText)) {
      violations.push(`${sourcePath}: ${violation.detail}`);
    }
  }
  violations.push(...clientServerDependencyViolations(sourceContents));
  violations.push(...(await verifyGlobalStyles()).map((violation) => `src/app/globals.css: ${violation}`));
  const cssPaths = (await listCssFiles(sourceRoot)).map(normalizeRelative).sort();
  const cssContents = new Map();
  for (const filePath of cssPaths) cssContents.set(filePath, await readFile(path.join(root, filePath), "utf8"));
  for (const [filePath, css] of cssContents) {
    if (filePath === "src/app/globals.css") continue;
    if (!filePath.endsWith(".module.css")) {
      violations.push(`${filePath}: Stylesheets must use CSS Modules except for app/globals.css.`);
      continue;
    }
    violations.push(...moduleStyleViolations(css, filePath).map((detail) => `${filePath}: ${detail}`));
  }

  if (violations.length > 0) {
    console.error("Frontend architecture violations:");
    for (const violation of violations) console.error(`- ${violation}`);
    process.exitCode = 1;
    return;
  }
  console.log(`Frontend architecture verified (${files.length} source files).`);
}

await main();
