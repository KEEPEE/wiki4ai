/**
 * Crypto utilities with fallback for insecure contexts (HTTP).
 *
 * Web Crypto API (`crypto.subtle`) is only available in secure contexts:
 * - HTTPS
 * - localhost
 *
 * When running on HTTP (e.g., http://192.168.x.x:3000), we provide pure JS fallbacks.
 */

// Check if Web Crypto API is available
const hasWebCrypto = typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined';

/**
 * SHA-256 hash implementation (pure JS fallback).
 * Based on RFC 6234 reference implementation.
 */
function sha256(message: Uint8Array): Uint8Array {
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ]);

  const H = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  ]);

  const msgLength = message.length;
  const bitLength = msgLength * 8;
  const paddedLength = ((msgLength + 8) >>> 6 << 6) + 64;
  const padded = new Uint8Array(paddedLength);
  padded.set(message);
  padded[msgLength] = 0x80;

  const view = new DataView(padded.buffer as ArrayBuffer);
  view.setUint32(paddedLength - 4, (bitLength >>> 24) & 0xff);
  view.setUint32(paddedLength - 8, bitLength >>> 0);

  for (let offset = 0; offset < paddedLength; offset += 64) {
    const W = new Uint32Array(64);

    for (let t = 0; t < 16; t++) {
      W[t] = view.getUint32(offset + t * 4, false);
    }

    for (let t = 16; t < 64; t++) {
      const s0 = rightRotate(W[t - 15], 7) ^ rightRotate(W[t - 15], 18) ^ (W[t - 15] >>> 3);
      const s1 = rightRotate(W[t - 2], 17) ^ rightRotate(W[t - 2], 19) ^ (W[t - 2] >>> 10);
      W[t] = (W[t - 16] + s0 + W[t - 7] + s1) | 0;
    }

    let [a, b, c, d, e, f, g, h] = H;

    for (let t = 0; t < 64; t++) {
      const S1 = rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + K[t] + W[t]) | 0;
      const S0 = rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) | 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) | 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) | 0;
    }

    H[0] = (H[0] + a) | 0;
    H[1] = (H[1] + b) | 0;
    H[2] = (H[2] + c) | 0;
    H[3] = (H[3] + d) | 0;
    H[4] = (H[4] + e) | 0;
    H[5] = (H[5] + f) | 0;
    H[6] = (H[6] + g) | 0;
    H[7] = (H[7] + h) | 0;
  }

  const output = new Uint8Array(32);
  for (let i = 0; i < 8; i++) {
    view.setUint32(i * 4, H[i], false);
  }
  return output;
}

function rightRotate(x: number, n: number): number {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

/**
 * PBKDF2 implementation using HMAC-SHA256 (pure JS fallback).
 */
async function pbkdf2HmacSha256(
  password: Uint8Array,
  salt: Uint8Array,
  iterations: number,
  keyLength: number
): Promise<Uint8Array> {
  const dk = new Uint8Array(keyLength);
  let blockNum = 1;
  let offset = 0;

  while (offset < keyLength) {
    const saltBlock = new Uint8Array(salt.length + 4);
    saltBlock.set(salt);
    const view = new DataView(saltBlock.buffer as ArrayBuffer);
    view.setUint32(salt.length, blockNum++, false);

    let u: Uint8Array = hmacSha256(password, saltBlock);
    const tLen = keyLength - offset > 32 ? 32 : keyLength - offset;
    let t = new Uint8Array(tLen);

    for (let i = 1; i < iterations; i++) {
      u = hmacSha256(password, u);
      for (let j = 0; j < t.length; j++) {
        t[j] ^= u[j];
      }
    }

    dk.set(t, offset);
    offset += tLen;
  }

  return dk;
}

function hmacSha256(key: Uint8Array, message: Uint8Array): Uint8Array {
  const block_size = 64;

  let k = key;
  if (k.length > block_size) {
    k = sha256(k);
  } else if (k.length < block_size) {
    const paddedKey = new Uint8Array(block_size);
    paddedKey.set(k);
    k = paddedKey;
  }

  const o_key_pad = new Uint8Array(block_size);
  const i_key_pad = new Uint8Array(block_size);

  for (let i = 0; i < block_size; i++) {
    o_key_pad[i] = k[i] ^ 0x5c;
    i_key_pad[i] = k[i] ^ 0x36;
  }

  const innerHashInput = new Uint8Array(i_key_pad.length + message.length);
  innerHashInput.set(i_key_pad);
  innerHashInput.set(message, i_key_pad.length);

  const innerHash = sha256(innerHashInput);

  const outerHashInput = new Uint8Array(o_key_pad.length + innerHash.length);
  outerHashInput.set(o_key_pad);
  outerHashInput.set(innerHash, o_key_pad.length);

  return sha256(outerHashInput);
}

/**
 * AES-GCM implementation using pure JS fallback.
 */
class AesGcm {
  private key: Uint32Array;

  constructor(keyBytes: Uint8Array) {
    this.key = new Uint32Array(80);
    const k = new Uint32Array(keyBytes.buffer as ArrayBuffer, keyBytes.byteOffset, 4);

    for (let i = 0; i < 4; i++) {
      this.key[i] = k[i];
    }

    let rcon = 1;
    for (let i = 4; i < 80; i += 4) {
      if ((i / 4) % 8 === 0) {
        this.key[i] = this.subWord(this.rotWord(this.key[i - 4])) ^ rcon;
        rcon <<= 1;
      } else if (i >= 24 && (i / 4) % 8 === 4) {
        this.key[i] = this.key[i - 4] ^ this.subWord(this.key[i - 8]);
      } else {
        this.key[i] = this.key[i - 4] ^ this.key[i - 16];
      }

      for (let j = 1; j < 4; j++) {
        this.key[i + j] = this.key[i + j - 4] ^ this.key[i + j - 16];
      }
    }
  }

  private subWord(word: number): number {
    let result = 0;
    for (let i = 3; i >= 0; i--) {
      const byte = (word >> (i * 8)) & 0xff;
      result |= this.sBox[byte] << (i * 8);
    }
    return result >>> 0;
  }

  private rotWord(word: number): number {
    return ((word << 8) | (word >>> 24)) >>> 0;
  }

  private readonly sBox = new Uint8Array([
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
  ]);

  encrypt(plaintext: Uint8Array, iv: Uint8Array): { ciphertext: Uint8Array; tag: Uint8Array } {
    const H = this.aesEncryptBlock(new Uint8Array(16));
    const J0 = this.gcmMultiply(H, new Uint8Array(iv.length === 12 ? 15 : iv.length + 1));

    let Y: Uint8Array = new Uint8Array(J0);
    const ciphertext = new Uint8Array(plaintext.length);

    for (let i = 0; i < plaintext.length; i += 16) {
      const block = new Uint8Array(16);
      block.set(Y);
      Y = this.aesEncryptBlock(block);

      const remaining = Math.min(16, plaintext.length - i);
      for (let j = 0; j < remaining; j++) {
        ciphertext[i + j] = plaintext[i + j] ^ Y[j];
      }
    }

    const aadLength = new Uint8Array(16);
    const dataLength = new Uint8Array(16);
    new DataView(aadLength.buffer as ArrayBuffer).setBigUint64(0, BigInt(0), false);
    new DataView(dataLength.buffer as ArrayBuffer).setBigUint64(0, BigInt(plaintext.length * 8), false);

    let tagY: Uint8Array = this.gcmMultiply(H, Y);
    tagY = this.xor(tagY, aadLength);
    tagY = this.gcmMultiply(H, tagY);
    tagY = this.xor(tagY, dataLength);
    const tag = this.gcmMultiply(H, tagY).slice(0, 16);

    return { ciphertext, tag };
  }

  decrypt(ciphertext: Uint8Array, iv: Uint8Array, tag: Uint8Array): Uint8Array | null {
    const H = this.aesEncryptBlock(new Uint8Array(16));
    const J0 = this.gcmMultiply(H, new Uint8Array(iv.length === 12 ? 15 : iv.length + 1));

    let Y: Uint8Array = new Uint8Array(J0);
    const plaintext = new Uint8Array(ciphertext.length);

    for (let i = 0; i < ciphertext.length; i += 16) {
      const block = new Uint8Array(16);
      block.set(Y);
      Y = this.aesEncryptBlock(block);

      const remaining = Math.min(16, ciphertext.length - i);
      for (let j = 0; j < remaining; j++) {
        plaintext[i + j] = ciphertext[i + j] ^ Y[j];
      }
    }

    const aadLength = new Uint8Array(16);
    const dataLength = new Uint8Array(16);
    new DataView(aadLength.buffer as ArrayBuffer).setBigUint64(0, BigInt(0), false);
    new DataView(dataLength.buffer as ArrayBuffer).setBigUint64(0, BigInt(ciphertext.length * 8), false);

    let tagY: Uint8Array = this.gcmMultiply(H, Y);
    tagY = this.xor(tagY, aadLength);
    tagY = this.gcmMultiply(H, tagY);
    tagY = this.xor(tagY, dataLength);
    const computedTag = this.gcmMultiply(H, tagY).slice(0, 16);

    let equal = 0;
    for (let i = 0; i < tag.length; i++) {
      equal |= computedTag[i] ^ tag[i];
    }

    return equal === 0 ? plaintext : null;
  }

  private aesEncryptBlock(block: Uint8Array): Uint8Array {
    const state = new Uint32Array(4);
    for (let i = 0; i < 4; i++) {
      state[i] = (block[i * 4] << 24) | (block[i * 4 + 1] << 16) | (block[i * 4 + 2] << 8) | block[i * 4 + 3];
    }

    for (let i = 0; i < 4; i++) {
      state[i] ^= this.key[i];
    }

    const rounds = 14;
    for (let r = 1; r <= rounds; r++) {
      for (let i = 0; i < 4; i++) {
        let word = state[i];
        let result = 0;
        for (let j = 3; j >= 0; j--) {
          const byte = (word >> (j * 8)) & 0xff;
          result |= this.sBox[byte] << (j * 8);
        }
        state[i] = result >>> 0;
      }

      let t = state[1];
      state[1] = ((t << 8) | (t >>> 24)) >>> 0;
      t = state[2];
      state[2] = ((t << 16) | (t >>> 16)) >>> 0;
      t = state[3];
      state[3] = ((t << 24) | (t >>> 8)) >>> 0;

      if (r < rounds) {
        for (let i = 0; i < 4; i++) {
          let col = state[i];
          const x1 = this.xtime(col & 0xff);
          const x2 = this.xtime(x1);
          const x3 = x1 ^ this.xtime(col >> 8 & 0xff);
          const x4 = x1 ^ this.xtime(col >> 16 & 0xff);
          const x5 = x1 ^ this.xtime(col >> 24 & 0xff);

          state[i] = (x2 | (x3 << 8) | (x4 << 16) | (x5 << 24)) >>> 0;
        }
      }

      for (let i = 0; i < 4; i++) {
        state[i] ^= this.key[r * 4 + i];
      }
    }

    const result = new Uint8Array(16);
    for (let i = 0; i < 4; i++) {
      result[i * 4] = (state[i] >>> 24) & 0xff;
      result[i * 4 + 1] = (state[i] >>> 16) & 0xff;
      result[i * 4 + 2] = (state[i] >>> 8) & 0xff;
      result[i * 4 + 3] = state[i] & 0xff;
    }

    return result;
  }

  private xtime(a: number): number {
    return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff;
  }

  private gcmMultiply(x: Uint8Array, y: Uint8Array): Uint8Array {
    const R = new DataView(new ArrayBuffer(16));
    R.setUint32(0, 0xe1000000);

    let z: Uint8Array = new Uint8Array(16);
    let v: Uint8Array = new Uint8Array(y.length);
    v.set(y);

    for (let i = 0; i < 128; i++) {
      if ((x[i >> 3] & (1 << (7 - (i % 8)))) !== 0) {
        z = this.xor(z, v);
      }

      const carry = v[15] & 0x01;
      for (let j = 15; j > 0; j--) {
        v[j] = ((v[j] >> 1) | (v[j - 1] << 7)) & 0xff;
      }
      v[0] = (v[0] >> 1) & 0xff;

      if (carry) {
        for (let j = 0; j < 4; j++) {
          const wordView = new DataView(v.buffer as ArrayBuffer, j * 4);
          wordView.setUint32(0, wordView.getUint32(0) ^ R.getUint32(j * 4));
        }
      }
    }

    return z;
  }

  private xor(a: Uint8Array, b: Uint8Array): Uint8Array {
    const result = new Uint8Array(a.length);
    for (let i = 0; i < a.length; i++) {
      result[i] = a[i] ^ b[i];
    }
    return result;
  }
}

/**
 * Crypto interface that works in both secure and insecure contexts.
 */
export const cryptoApi = {
  async sha256(data: Uint8Array): Promise<Uint8Array> {
    if (hasWebCrypto) {
      const hashBuffer = await crypto.subtle.digest('SHA-256', data as BufferSource);
      return new Uint8Array(hashBuffer);
    }
    return sha256(data);
  },

  async deriveKey(
    password: string,
    salt: Uint8Array,
    iterations: number = 100_000
  ): Promise<Uint8Array> {
    if (hasWebCrypto) {
      const encoder = new TextEncoder();
      const keyMaterial = await crypto.subtle.importKey(
        'raw',
        encoder.encode(password),
        'PBKDF2',
        false,
        ['deriveKey']
      );

      const key = await crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
        keyMaterial,
        { name: 'AES-GCM', length: 256 },
        true, // extractable: true - needed to export raw key bytes for use with encrypt/decrypt methods
        ['encrypt', 'decrypt']
      );

      const rawKey = await crypto.subtle.exportKey('raw', key);
      return new Uint8Array(rawKey);
    }

    const encoder = new TextEncoder();
    return pbkdf2HmacSha256(encoder.encode(password), salt, iterations, 32);
  },

  async encrypt(data: string, keyBytes: Uint8Array): Promise<{ ciphertextWithTag: Uint8Array; iv: Uint8Array }> {
    const encoder = new TextEncoder();
    const plaintext = encoder.encode(data);
    const iv = crypto.getRandomValues(new Uint8Array(12));

    if (hasWebCrypto) {
      const key = await crypto.subtle.importKey(
        'raw',
        keyBytes as BufferSource,
        { name: 'AES-GCM' },
        false,
        ['encrypt']
      );

      const ciphertextBuffer = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        key,
        plaintext
      );

      return { ciphertextWithTag: new Uint8Array(ciphertextBuffer), iv };
    }

    const aesGcm = new AesGcm(keyBytes);
    const result = aesGcm.encrypt(plaintext, iv);

    const combined = new Uint8Array(result.ciphertext.length + 16);
    combined.set(result.ciphertext);
    combined.set(result.tag, result.ciphertext.length);

    return { ciphertextWithTag: combined, iv };
  },

  async decrypt(ciphertextWithTag: Uint8Array, iv: Uint8Array, keyBytes: Uint8Array): Promise<string | null> {
    if (hasWebCrypto) {
      const key = await crypto.subtle.importKey(
        'raw',
        keyBytes as BufferSource,
        { name: 'AES-GCM' },
        false,
        ['decrypt']
      );

      try {
        const plaintextBuffer = await crypto.subtle.decrypt(
          { name: 'AES-GCM', iv } as AesGcmParams,
          key,
          ciphertextWithTag.slice() as BufferSource
        );
        return new TextDecoder().decode(plaintextBuffer);
      } catch {
        return null;
      }
    }

    const ciphertext = ciphertextWithTag.slice(0, -16);
    const tag = ciphertextWithTag.slice(-16);

    const aesGcm = new AesGcm(keyBytes);
    const plaintext = aesGcm.decrypt(ciphertext, iv, tag);
    if (!plaintext) return null;
    return new TextDecoder().decode(plaintext);
  },

  get isSecureContext(): boolean {
    return hasWebCrypto;
  }
};
