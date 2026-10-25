package com.wiki4ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiki4ai.config.JwtAuthenticationFilter.ApiTokenAuthenticationDetails;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.model.Actor;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.repository.ActorRepository;
import com.wiki4ai.repository.ApiTokenRepository;
import com.wiki4ai.repository.ProjectActorRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the lease TTL is configuration and not a constant (WIKI4AI-142).
 * <p>
 * A separate context with {@code wiki4ai.work-items.lease-minutes=7}: the claim
 * then produces a seven-minute lease. The 45-minute default is asserted in
 * {@code WorkItemLeaseIntegrationTest} against the unmodified context — together
 * the two show the number is read from the property, because the only thing that
 * differs between them is that property.
 * <p>
 * Rule 5b applies here too: this class writes to the shared H2, so it cleans up in
 * {@code @BeforeEach} and {@code @AfterEach}, children before parents, and it does
 * not use {@code @DirtiesContext}.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@TestPropertySource(properties = "wiki4ai.work-items.lease-minutes=7")
@DisplayName("Work item lease TTL is configurable (WIKI4AI-142)")
class WorkItemLeaseConfigTest {

    private static final String OWNER = "wi142cfg_owner";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Autowired
    private WorkItemRepository workItemRepository;

    @Autowired
    private WorkItemLeaseReaper leaseReaper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectActorRepository projectActorRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private ApiTokenRepository apiTokenRepository;

    @Autowired
    private ActorRepository actorRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectService projectService;

    private Long actorId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cleanState();

        User user = new User();
        user.setUsername(OWNER);
        user.setEmail(OWNER + "@test.local");
        user.setPassword("not-a-real-hash");
        user.setRole(Role.USER);
        userRepository.save(user);

        Actor agent = new Actor();
        agent.setHandle("wi142cfg-agent");
        agent.setKind(Actor.KIND_AGENT);
        agent.setOwnerUserId(user.getId());
        actorId = actorRepository.save(agent).getId();

        projectId = projectService.createProject(
                ProjectCreateDTO.builder().name("WI142 Cfg").visibility(Project.VISIBILITY_PUBLIC).build(),
                OWNER).getId();

        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanState();
    }

    private void cleanState() {
        workItemRepository.deleteAll();
        projectActorRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        apiTokenRepository.deleteAll();
        actorRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.findByUsername(OWNER).ifPresent(userRepository::delete);
    }

    private void authenticate() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new ApiTokenAuthenticationDetails(1L,
                userRepository.findByUsername(OWNER).orElseThrow().getId(), actorId, List.of("read", "write")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("with lease-minutes=7 the claim grants a 7 minute lease")
    void leaseMinutesComeFromTheProperty() throws Exception {
        assertThat(environment.getProperty("wiki4ai.work-items.lease-minutes")).isEqualTo("7");

        Long id = createReadyItem();
        mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk());

        WorkItem item = workItemRepository.findById(id).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        assertThat(item.getLeaseExpiresAt())
                .as("the TTL followed the property instead of the 45 minute default")
                .isBetween(now.plusMinutes(6), now.plusMinutes(8));
        assertThat(item.getLeaseExpiresAt())
                .as("and it is still a LocalDateTime, never an Instant (SPEC rule 11b)")
                .isInstanceOf(LocalDateTime.class);
    }

    @Test
    @DisplayName("a 7 minute lease is reaped exactly like a 45 minute one")
    void configuredLeaseIsStillReaped() throws Exception {
        Long id = createReadyItem();
        mockMvc.perform(post("/api/v1/work-items/" + id + "/claim"))
                .andExpect(status().isOk());

        WorkItem item = workItemRepository.findById(id).orElseThrow();
        item.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        workItemRepository.save(item);

        assertThat(leaseReaper.releaseExpired()).isEqualTo(1);
        WorkItem released = workItemRepository.findById(id).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(WorkItem.STATUS_READY);
        assertThat(released.getAssigneeActorId()).isNull();
        assertThat(released.getClaimToken()).isNull();
        assertThat(released.getLeaseExpiresAt()).isNull();
    }

    private Long createReadyItem() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/work-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":" + projectId + ",\"title\":\"Configured lease\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put("/api/v1/work-items/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ready\"}"))
                .andExpect(status().isOk());
        return id;
    }
}
