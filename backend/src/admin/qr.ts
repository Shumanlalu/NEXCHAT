import QRCode from "qrcode";

/**
 * Render an otpauth:// URI as an inline PNG. Returning a data URL keeps the
 * secret on this server and inside the nonce-CSP admin page: no third-party QR
 * service ever sees it, and `img-src 'self' data:` already permits the result.
 */
export function totpQrDataUri(uri: string): Promise<string> {
  return QRCode.toDataURL(uri, {
    errorCorrectionLevel: "M",
    margin: 1,
    width: 220,
    color: { dark: "#0b1020ff", light: "#ffffffff" },
  });
}
