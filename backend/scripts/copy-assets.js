const fs = require("node:fs");
const path = require("node:path");

fs.mkdirSync(path.join(__dirname, "..", "dist"), { recursive: true });
fs.copyFileSync(
  path.join(__dirname, "..", "src", "schema.sql"),
  path.join(__dirname, "..", "dist", "schema.sql"),
);
