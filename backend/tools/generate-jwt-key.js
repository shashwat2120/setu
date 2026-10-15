#!/usr/bin/env node
// Generates an EC P-256 key pair for signing Setu access tokens (ES256) and prints the two values the Identity service reads:
//   JWT_SIGNING_KEY          Base64 PKCS#8 DER of the PRIVATE key (secret; only the Identity service holds it)
//   JWT_PREVIOUS_PUBLIC_KEYS Base64 X.509 DER of the PUBLIC key (what to put in this variable once the key is retired, during a rotation)
//
// Rotation: generate a new pair, set JWT_SIGNING_KEY to the new private key, and add the OLD public key to JWT_PREVIOUS_PUBLIC_KEYS
// (comma-separated for several). Tokens signed with the old key keep verifying for one access-token lifetime (15 minutes); remove it after.
//
//   node backend/tools/generate-jwt-key.js
const { generateKeyPairSync } = require('node:crypto');

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const priv = privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64');
const pub = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');

console.log('# Keep the private key secret. Do not commit it.');
console.log(`JWT_SIGNING_KEY=${priv}`);
console.log(`# Public half (use as JWT_PREVIOUS_PUBLIC_KEYS after rotating away from this key):`);
console.log(`PUBLIC_KEY=${pub}`);
