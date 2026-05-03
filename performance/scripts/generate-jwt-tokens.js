#!/usr/bin/env node

const crypto = require('crypto');

const count = Number(process.argv[2] || process.env.MEMBER_COUNT || 100000);
const outputHeader = process.env.NO_HEADER !== 'true';
const secret = process.env.JWT_SECRET || 'secret_key';
const expiresInSeconds = Number(process.env.JWT_EXPIRES_IN_SECONDS || 7 * 24 * 60 * 60);
const now = Math.floor(Date.now() / 1000);
const exp = now + expiresInSeconds;

if (outputHeader) {
  console.log('memberId,token');
}

for (let memberId = 1; memberId <= count; memberId += 1) {
  const token = signJwt({
    sub: String(memberId),
    email: `perf${memberId}@example.com`,
    roles: ['MEMBER'],
    exp,
  });

  console.log(`${memberId},${token}`);
}

function signJwt(payload) {
  const header = {
    alg: 'HS256',
    typ: 'JWT',
  };

  const encodedHeader = base64url(JSON.stringify(header));
  const encodedPayload = base64url(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = crypto
    .createHmac('sha256', secret)
    .update(signingInput)
    .digest('base64url');

  return `${signingInput}.${signature}`;
}

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}
