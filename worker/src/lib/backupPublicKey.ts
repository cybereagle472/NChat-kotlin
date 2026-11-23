/**
 * Backup encryption public key (RSA-4096, OAEP-SHA-256).
 *
 * The nightly backup job wraps a fresh per-file AES-256-GCM key with this
 * key — nothing else. The matching PRIVATE key exists only with the NChat
 * owner (delivered once, never committed, never configured on Cloudflare),
 * so neither the platform nor anyone who compromises the account can read
 * the stored backups. Public keys are safe to publish; treat anything named
 * "private key" as the vault itself.
 */
export const BACKUP_PUBLIC_KEY_PEM = `-----BEGIN PUBLIC KEY-----
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAzi7MkpBnXWw7FD/TuyvG
OZyT78DZyhSI3tyyikiRutsVLZihdrilTiA4D9NgQKfZ+jVD4SOSFd69cXISXns5
vWMGvYpNV0ixv6KoHr5pH1ho9KgUXCHTkeOutVIEWKRfAS68iv56+tA+xVfXWImL
m6t65ab14v28ZXlZEFW1w2h5acHvR2Ylx9BwKAyBwbD44uhcmM+2ymBQAQqWQlv8
ILAJykER6wqtvUiHicA2dKSD4kWPcpEvhYkdgILrUWyQsQrLNPxWSxxBh7Oqnrh1
smxB8+XyD0JXxhn7ivwbK2YcZqUL5A9HCjapEvhuRCso6pUe+O6GedM/12HTA/j+
/EyBjhN+bcJfiJe7mtuSZkISz1A3s8UaOmkFi8JrNuRsLUS2ha4h2hPemKjRfP0T
+7DWfYeKOBF3JUcs26PBckH84IRqgEpcdEulbcKroHAwkv5nkBjanXRicCUVZuF9
oL5+/YsnF7nSVldNIW6IJ1XzqgfSQNQXcY7syuiFSxqqem9QOCiiWGFi5f7SrpGK
9nElNYMYXELvC2WyycBA3aeGQaLRq4/aSCAeS/chmci1cMQeQmItRrjaClSDjGKm
25uIZYYWi9Ned14L2aeC3baIKVf3/JCe6MTmX6TlP2ldtH06Z3ijyYkNSqHyP7f5
a7wFrN9+tdrJ4brZnIsQO4sCAwEAAQ==
-----END PUBLIC KEY-----
`;
