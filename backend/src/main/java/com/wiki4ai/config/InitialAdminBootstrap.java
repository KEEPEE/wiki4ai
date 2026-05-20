package com.wiki4ai.config;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Bootstrap runner that creates the initial admin user on first application startup.
 * <p>
 * This component runs only when {@code security.enabled=true} (the default).
 * It checks if the database contains any users; if not, it creates an admin user
 * with credentials from {@link InitialAdminProperties}.
 */
@Configuration
@EnableConfigurationProperties(InitialAdminProperties.class)
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class InitialAdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    private final UserRepository userRepository;
    private final InitialAdminProperties properties;
    private final BCryptPasswordEncoder passwordEncoder;

    public InitialAdminBootstrap(UserRepository userRepository,
                                  InitialAdminProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public void run(String... args) {
        // Only create admin if the database is empty
        if (userRepository.count() > 0) {
            log.info("Database already contains {} user(s). Skipping initial admin bootstrap.", userRepository.count());
            return;
        }

        String username = properties.username();
        String rawPassword = properties.password();

        // Validate that credentials are configured
        if (username == null || username.isBlank()) {
            log.warn("Skipping initial admin creation: admin.initial.username is not set.");
            return;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("Skipping initial admin creation: admin.initial.password is not set.");
            return;
        }

        // Create the admin user with BCrypt hashed password
        String email = username + "@localhost";
        User adminUser = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.ADMIN)
                .build();

        userRepository.save(adminUser);

        log.info("Initial admin user created: username='{}', role=ADMIN", username);
    }
}
