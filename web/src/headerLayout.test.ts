/**
 * The shape of the header's phone CSS (`index.css`) that keeps it inside its gutter. jsdom has no layout, so what these tests can do is
 * hold the RULES that do the work; the measurement itself — a signed-in header at 390, 360 and 320 px with a 24-character nickname and
 * 10,000 PLAY in play — is `scripts/check-header-width.mjs` (headless Chrome), which `web/README.md` says how to run.
 *
 * First tester pass, D12 and its follow-up: with the header's controls made 40 px tall, a fixed cap on the nickname (68 px at 320) no
 * longer left a gutter — the last control ended 1.3 px from the edge. A cap has to be re-tuned every time anything else in the row
 * grows; so the nickname is what GIVES WAY (it takes the width the rest leaves) and the rest keeps its size.
 */
import { describe, expect, it } from "vitest";
import css from "./index.css?raw";

/** The text between the braces of the block that starts at `header` (a rule or an `@media`), nesting respected. */
function blockAfter(source: string, header: string): string {
  const start = source.indexOf(header);
  if (start < 0) throw new Error(`index.css has no "${header}"`);
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    if (source[i] === "}" && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error(`index.css: "${header}" is never closed`);
}

/** The declarations of the rule for `selector` inside `block`, as one string with the whitespace collapsed. */
function declarations(block: string, selector: string): string {
  return blockAfter(block, `${selector} {`).replace(/\/\*[\s\S]*?\*\//g, "").replace(/\s+/g, " ").trim();
}

const phone = blockAfter(css, "@media (max-width: 720px)");
const narrow = blockAfter(css, "@media (max-width: 340px)");

describe("the header's phone CSS: the nickname gives way, the rest keeps its size", () => {
  it("the group of controls may be narrower than its parts add up to — the nickname is what takes up the difference", () => {
    expect(declarations(phone, ".app-header__right")).toMatch(/min-width: 0;/);
  });

  it("the logo, the sound switch, the balance pill and CONNECT WALLET do not shrink (squeezed, the pill lost its coin)", () => {
    expect(declarations(phone, ".app-header__logo,\n  .app-header__right > :not(.profile-menu)")).toMatch(/flex: none;/);
  });

  it("the nickname button is capped by what its wrapper has left, not by a number alone, and never below a thumb", () => {
    const button = declarations(phone, ".profile-menu__button");
    expect(button).toMatch(/max-width: min\(\d+px, 100%\);/);
    expect(button).toMatch(/min-width: 4\dpx;/);
    expect(button).toMatch(/overflow: hidden; text-overflow: ellipsis; white-space: nowrap;/);
    expect(declarations(phone, ".profile-menu")).toMatch(/min-width: 0; flex: 0 1 auto;/);
  });

  it("at 320 the cap is the same kind of rule — a fixed 68 px was what ran into the gutter — and the gaps are a little tighter", () => {
    expect(declarations(narrow, ".profile-menu__button")).toMatch(/max-width: min\(\d+px, 100%\);/);
    expect(narrow).not.toMatch(/max-width: \d+px/);
    expect(declarations(narrow, ".app-header")).toMatch(/gap: 6px;/);
    expect(declarations(narrow, ".app-header__right")).toMatch(/gap: 6px;/);
  });
});
