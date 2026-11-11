package com.wiki4ai.config;

import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.ReservedUsernames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Names, at startup, the accounts that already carry a reserved username
 * (WIKI4AI-197, REV-2 P0 #4).
 *
 * <p>Rejecting {@code anonymous} at registration and at authentication is what closes
 * the hole, but it does not remove rows that were created before the rule existed. Such
 * an account now gets a 401 on every request, because its {@code sub} is the sentinel
 * the permission layer compares against. That is the correct fail-closed result — the
 * alternative is trusting a name that cannot be told apart from "nobody" — but it is
 * also invisible: the user simply cannot sign in any more, and nothing in the request
 * path says why.
 *
 * <p>So the deployment is told once, at boot, with the id an admin needs to rename the
 * row. The username is deliberately <b>not</b> rewritten by a Flyway migration: it is
 * the JWT {@code sub} and the attribution key on every document and permission grant
 * that account ever touched, so renaming it silently would move that history.
 *
 * <p>Logged at ERROR rather than WARN because the operator has to do something: an
 * account in this list is locked out until an admin renames it.
 */
@Component
@Slf4j
public class ReservedUsernameStartupCheck implements ApplicationRunner {

    private final UserRepository userRepository;

    public ReservedUsernameStartupCheck(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Object[]> rows;
        try {
            rows = userRepository.findIdAndUsernameByLowercasedUsernameIn(
                    ReservedUsernames.allReservedLowercase());
        } catch (RuntimeException diagnosticFailure) {
            // This is a report, not a gate. A database that is not ready yet, or a
            // profile without a users table, must not turn a working boot into a failed
            // one — and it must not become a stack trace either.
            log.warn("Could not check for reserved usernames at startup ({}); skipping the report.",
                    diagnosticFailure.getMessage());
            return;
        }

        for (Object[] row : rows) {
            // id, username — projected, see UserRepository#findIdAndUsernameByLowercasedUsernameIn
            log.error("Account id={} is named '{}', which is a reserved name: it is the marker this "
                            + "application uses for an unauthenticated caller. It now receives 401 on "
                            + "every request. An ADMIN must rename it (the username is the JWT subject "
                            + "and the attribution key, so the rename is deliberate, not a migration).",
                    row[0], row[1]);
        }

        if (!rows.isEmpty()) {
            log.error("{} account(s) carry a reserved username and are locked out until renamed.",
                    rows.size());
        }
    }

    /** Convenience for tests: the names this check looks for. */
    static Collection<String> reservedNames() {
        return ReservedUsernames.allReservedLowercase();
    }
}
