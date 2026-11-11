package com.wiki4ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReservedUsernames} (WIKI4AI-197, REV-2 P0 #4).
 *
 * <p>The rule is small, so the tests are mostly about its edges: the two names that
 * must be refused, the case-insensitivity that stops {@code Anonymous} slipping past a
 * check written against the lowercase literal, and the names that must NOT be refused —
 * because every false positive here is a real user who cannot register.
 */
class ReservedUsernamesTest {

    @Nested
    @DisplayName("isReserved")
    class IsReserved {

        @Test
        void refusesTheTwoMarkerNames() {
            assertTrue(ReservedUsernames.isReserved("anonymous"));
            assertTrue(ReservedUsernames.isReserved("anonymoususer"));
        }

        @Test
        void matchingIsCaseInsensitive() {
            // The username uniqueness this application enforces is case-sensitive, so
            // 'Anonymous' is a DIFFERENT account as far as the unique index is concerned.
            // A case-sensitive reserved check would let it through and reintroduce the
            // exact collision the sentinel comparison makes.
            assertTrue(ReservedUsernames.isReserved("Anonymous"));
            assertTrue(ReservedUsernames.isReserved("ANONYMOUS"));
            assertTrue(ReservedUsernames.isReserved("AnonymousUser"));
            assertTrue(ReservedUsernames.isReserved("ANONYMOUsUSER"));
        }

        @Test
        void surroundingWhitespaceDoesNotHideTheName() {
            // Usernames are trimmed on the way in elsewhere, but the authentication path
            // reads a JWT claim it cannot assume anyone normalised.
            assertTrue(ReservedUsernames.isReserved(" anonymous "));
            assertTrue(ReservedUsernames.isReserved("\tanonymoususer\n"));
        }

        @Test
        void allowsNamesThatOnlyContainTheMarker() {
            // False positives are the risk on this side of the rule: 'anonymous_reporter'
            // is an ordinary name and its owner must be able to sign up. Substring
            // matching would be the lazy implementation and it would be wrong.
            assertFalse(ReservedUsernames.isReserved("anonymous_reporter"));
            assertFalse(ReservedUsernames.isReserved("theanonymous"));
            assertFalse(ReservedUsernames.isReserved("anonymoususer2"));
            assertFalse(ReservedUsernames.isReserved("anonymous_user"));
            assertFalse(ReservedUsernames.isReserved("user"));
            assertFalse(ReservedUsernames.isReserved("admin"));
        }

        @Test
        void nullAndBlankAreNotReserved() {
            // They are not names at all. Callers that must reject them have their own
            // validation, and conflating the two would make the rejection message lie.
            assertFalse(ReservedUsernames.isReserved(null));
            assertFalse(ReservedUsernames.isReserved(""));
            assertFalse(ReservedUsernames.isReserved("   "));
        }

        @Test
        void theSentinelIsItselfReserved() {
            // The invariant the whole rule rests on: the literal the controllers fall
            // back to is in the reserved set. If these two ever drift apart, the check
            // stops protecting the thing it is named after.
            assertTrue(ReservedUsernames.isReserved(ReservedUsernames.SENTINEL));
            assertTrue(ReservedUsernames.allReservedLowercase()
                    .contains(ReservedUsernames.SENTINEL));
        }

        @Test
        void theExposedSetIsTheLowercaseFormAndIsNotMutable() {
            assertTrue(ReservedUsernames.allReservedLowercase().stream()
                    .allMatch(name -> name.equals(name.toLowerCase())));
            assertThrows(UnsupportedOperationException.class,
                    () -> ReservedUsernames.allReservedLowercase().add("sneaky"),
                    "a caller must not be able to widen the set from outside");
        }
    }

    @Nested
    @DisplayName("rejectIfReserved")
    class RejectIfReserved {

        @Test
        void throwsForAReservedNameAndNamesItInTheMessage() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ReservedUsernames.rejectIfReserved("anonymous"));
            assertTrue(e.getMessage().contains("anonymous"),
                    "the message must say which name was refused, was: " + e.getMessage());
            assertTrue(e.getMessage().contains("reserved"),
                    "and why, was: " + e.getMessage());
        }

        @Test
        void messageNeverEchoesNullAsTheName() {
            assertDoesNotThrow(() -> ReservedUsernames.rejectIfReserved(null));
            assertDoesNotThrow(() -> ReservedUsernames.rejectIfReserved("keeps-working"));
        }

        @Test
        void usesIllegalArgumentExceptionLikeTheNeighbouringRules() {
            // "Username is already taken" and "Email is already registered" are thrown
            // the same way, so GlobalExceptionHandler answers one body shape for "that
            // name will not work here" instead of two.
            assertEquals(IllegalArgumentException.class,
                    assertThrows(IllegalArgumentException.class,
                            () -> ReservedUsernames.rejectIfReserved("ANONYMOUSUSER")).getClass());
        }
    }
}
