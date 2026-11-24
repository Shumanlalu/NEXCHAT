const { randomBytes } = require("node:crypto");

// Generate a high-entropy break-glass value locally. Add it to Vercel as
// ADMIN_SETUP_TOKEN and never commit it, email it, or expose it to the browser.
const token = randomBytes(32).toString("base64url");

process.stderr.write(
  "Copy this value into the Vercel environment variable ADMIN_SETUP_TOKEN (Production):\n",
);
process.stdout.write(`${token}\n`);
