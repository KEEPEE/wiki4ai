package com.wiki4ai.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * WIKI4AI-197 (D1): the digest a v2 re-encryption request is compared against.
 *
 * <p>The client asks the server to move one row from v1 to v2 and, in the same breath,
 * states which v1 bytes it was looking at when it produced the v2 ones. The server
 * compares that statement with the bytes it holds right now; if they differ, something
 * wrote to the row between the read and the request, and the v2 ciphertext the client
 * just sent was built from a snapshot nobody has any more. That is a compare-and-set,
 * and it is the only guard that also works against writers that know nothing about the
 * v2 columns — a rolled-back image, or the MCP server — because it is about the v1
 * bytes themselves, not about a version counter they never touch.
 *
 * <p>Why not {@code @Version}: an optimistic-lock column is maintained by Hibernate,
 * and the old image's entity class has no such field, so it would never increment it.
 * The CAS would silently pass every time.
 *
 * <p>The layout is length-prefixed rather than delimiter-separated because every input
 * is an arbitrary byte array: a separator can occur inside a ciphertext by chance, a
 * length cannot. A null field contributes {@code 0xFFFFFFFF} and no bytes, and an empty
 * array contributes {@code 0x00000000} — the two are distinct states in this schema
 * (an absent field is stored NULL, and the old writer only ever stores NULL for it),
 * and collapsing them would let a write that turned NULL into empty bytes pass as
 * unchanged.
 *
 * <p>Nothing about the plaintext is in here. It is a digest of ciphertexts, so it can
 * be sent to and from the client without revealing anything the client does not
 * already hold.
 */
public final class VaultV1Digest {

    /**
     * Domain separator, so a digest computed over this layout cannot collide with a
     * SHA-256 taken over the same bytes in some other order.
     */
    private static final byte[] DOMAIN = "w4ai-vault-v1".getBytes(StandardCharsets.UTF_8);

    private static final int NULL_FIELD = -1;

    private VaultV1Digest() {
    }

    /**
     * Compute the digest of a row's v1 bytes.
     *
     * @param usernameEncrypted v1 username ciphertext, or null if the row has none
     * @param passwordEncrypted v1 password ciphertext, or null if the row has none
     * @param notesEncrypted    v1 notes ciphertext, or null if the row has none
     * @param iv                the v1 initialisation vector shared by the three fields
     * @return lowercase hex SHA-256, 64 characters
     */
    public static String of(byte[] usernameEncrypted, byte[] passwordEncrypted,
                            byte[] notesEncrypted, byte[] iv) {
        MessageDigest digest = newDigest();
        digest.update(DOMAIN);
        digest.update((byte) 0);
        for (byte[] field : new byte[][]{usernameEncrypted, passwordEncrypted, notesEncrypted, iv}) {
            if (field == null) {
                digest.update(int32(NULL_FIELD));
            } else {
                digest.update(int32(field.length));
                digest.update(field);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec; a runtime without it is not a
            // condition a caller can act on.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
