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
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA1o2g/c3Zyov+fFCUn8zz
8HXhM59PrXXqPOiD+GNkX6KNsG4iiymA9g1YgkVxNMZv3R5avaW4kDi6gLegx/Lc
Lt5u42fkWKSKLBYX7PBnF/pKa9GvIb2/8TBxZWx/fABRp+lPHdlRNa1vfW3rylBJ
PNmCuDvYG8KeyBx6k2fhV9sHsJ2OtRBftAQaz1cAFVAgnxvIXLrBKut15hIivWVV
x+jVOirq3dfsAnS6pJ6FzaDH6z3+1mBGeawo9Wp7zRSJKF27Qm3cfIOJt4Rmjijc
VaCVgBNKg1ncU9CHtsULcpOur756tbI6hf4b/IfH0JYAOF5VuCVS30XzxmhbSYkU
CWwHciub7qukPD84XbyOR0lJ2RleQa6UAAv/KQe6xo7AkIXIXsr9e/1j3x7maKVN
9jXb8ZramUzipvM16QFiajVD5VKkkKBCg4FeJqeYVxbfiwZzdVTOi/yoK1hX03Ek
rv3cUIjiwdRkdu3/Yvjc/9xrY9XwVLhuwz4DfmYQbOf2pcHw3XKq993k0bg+zjzu
OVth7hgVXjay6rTKn8SpZjMxypY0hrFkvTCYS9hrUmnC+WOY3mWNuSfwPs2Jm2/b
gkFsNhi/U/PoclMjCKA7COHBqhRHRpKjQXHzsVqfOIzIHvEEzviPzDq1p6uIQbIg
QWyGlgzz7zivYiAT0r3DPn0CAwEAAQ==
-----END PUBLIC KEY-----`;
