package com.wiki4ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WIKI4AI-197 (D1): the second half of the v2 key initialisation — the wrapped DEK
 * and the fingerprint the client will stamp on every entry it writes.
 *
 * <p>The server stores both without understanding either. It cannot open the wrapped
 * key, because opening it needs the master password and the v2 flow never sends one;
 * that is the property the whole hierarchy exists to provide, and it is why the key
 * is a random DEK wrapped under a password-derived KEK rather than a key derived from
 * the password directly. Re-encrypting the vault after a password change then means
 * re-wrapping these bytes once, not touching a single entry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultV2InitKeyRequestDTO {

    /** Base64 of the DEK wrapped under the KEK derived from the raw password. */
    @NotBlank(message = "Wrapped key is required")
    private String wrappedKey;

    /** Fingerprint of the DEK. Recorded on every v2 entry; not a secret. */
    @NotBlank(message = "Key id is required")
    @Size(max = 64, message = "Key id must not exceed 64 characters")
    private String keyId;
}
