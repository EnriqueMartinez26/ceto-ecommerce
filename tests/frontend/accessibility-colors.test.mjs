import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const globalStyles = await readFile(new URL("../../src/app/globals.css", import.meta.url), "utf8");

function colorToken(name) {
  const match = globalStyles.match(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6});`));
  assert.ok(match, `Expected --${name} to be a six-digit color token.`);
  return match[1];
}

function luminance(hexColor) {
  const channels = hexColor.slice(1).match(/.{2}/g).map((channel) => Number.parseInt(channel, 16) / 255);
  const linear = channels.map((channel) => channel <= 0.04045
    ? channel / 12.92
    : ((channel + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

function contrastRatio(foreground, background) {
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

test("semantic text colors meet WCAG AA on their assigned surfaces", () => {
  const white = colorToken("surface");
  const pairs = [
    [colorToken("brand"), white],
    [colorToken("brand"), colorToken("brand-soft")],
    [white, colorToken("brand")],
    [white, colorToken("brand-hover")],
    [white, colorToken("brand-active")],
    [colorToken("brand-on-dark"), colorToken("ink")],
    [colorToken("natural-dark"), colorToken("natural-soft")],
    [colorToken("ink-subtle"), colorToken("bg-soft")],
    [colorToken("ink-muted"), colorToken("disabled")],
    [colorToken("forest"), colorToken("paper")],
    [colorToken("forest"), colorToken("paper-green")],
    [colorToken("ink-muted"), colorToken("paper-green")],
    [colorToken("brand"), colorToken("paper")],
    [white, colorToken("forest")],
    [colorToken("brand-on-dark"), colorToken("forest-deep")],
    [white, colorToken("forest-deep")],
  ];

  for (const [foreground, background] of pairs) {
    assert.ok(
      contrastRatio(foreground, background) >= 4.5,
      `${foreground} on ${background} must reach 4.5:1 for normal text.`,
    );
  }
});

test("the focus ring remains distinguishable on light and dark surfaces", () => {
  const focusColor = colorToken("natural-dark");

  for (const background of [colorToken("bg-main"), colorToken("ink")]) {
    assert.ok(
      contrastRatio(focusColor, background) >= 3,
      `${focusColor} on ${background} must reach 3:1 for a non-text focus indicator.`,
    );
  }
});
