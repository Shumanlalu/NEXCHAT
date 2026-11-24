import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import path from "node:path";
import { describe, it } from "node:test";

const SCRIPT = path.join(__dirname, "..", "scripts", "generate-admin-setup-token.js");

function generate(): string {
  return execFileSync(process.execPath, [SCRIPT], { encoding: "utf8" }).trim();
}

describe("admin setup token generator", () => {
  it("prints a fresh high-entropy token without any surrounding text on stdout", () => {
    const first = generate();
    const second = generate();

    assert.match(first, /^[A-Za-z0-9_-]{43}$/);
    assert.match(second, /^[A-Za-z0-9_-]{43}$/);
    assert.notEqual(first, second);
  });
});
