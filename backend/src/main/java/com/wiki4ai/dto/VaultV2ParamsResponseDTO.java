package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WIKI4AI-197 (D1): everything a v2 client needs in order to reach its own data
 * encryption key, and nothing the server could use to reach it.
 *
 * <p>The wrapped key is not a secret in transit. It is a DEK sealed under a key
 * derived from the master password, and the only party holding that password is the
 * client. Returning it is what makes the write-once init recoverable: if a browser
 * loses the DEK it generated, or a second browser opens the same vault after the
 * first one committed the key, the second one downloads this value and unwraps it
 * with the password it was just given.
 *
 * <p>{@code initialized} is the flag a client branches on. Before it is true the
 * client has no DEK to unwrap and authenticates by decrypting one v1 entry; after it
 * is true the DEK is the authenticator, and the v1 verifier is a sentinel that
 * fails on purpose.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultV2ParamsResponseDTO {

    private boolean initialized;

    /** Base64 of the server-generated salt. Null until init-salt has run. */
    private String salt;

    /**
     * JSON describing how to turn a password into the KEK, e.g.
     * {@code {"alg":"PBKDF2-HMAC-SHA256","iter":600000,"dkLen":256}}. Stored rather
     * than hardcoded so raising the work factor later does not invalidate a key that
     * is already wrapped.
     */
    private String kdf;

    /** Base64 of the DEK wrapped under the KEK. Null until init-key has run. */
    private String wrappedKey;

    /** Fingerprint of the DEK, recorded on every v2 entry. Null until init-key. */
    private String keyId;
}
