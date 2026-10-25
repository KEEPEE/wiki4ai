package com.wiki4ai.service;

import com.wiki4ai.dto.WorkItemClaimDTO;
import com.wiki4ai.dto.WorkItemDTO;
import com.wiki4ai.dto.WorkItemRequestDTO;
import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.model.Project;
import com.wiki4ai.model.ProjectActor;
import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.model.WorkItem;
import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
import com.wiki4ai.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WorkItemService} (WIKI4AI-140 / WIKI4AI-141).
 * <p>
 * No database and no Spring context: the rules under test are pure service-layer
 * rules — the state machine, ownership (404 for a foreign private project, 403
 * for a foreign public one), attribution coming from the resolver and nowhere
 * else, and the claim's "0 rows updated means conflict". The real atomicity of
 * the claim against a real database is proven in {@code WorkItemClaimIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkItemService (WIKI4AI-140 + 141)")
class WorkItemServiceTest {

    private static final String OWNER = "wi_owner";
    private static final String STRANGER = "wi_stranger";
    private static final Long OWNER_ID = 100L;
    private static final Long STRANGER_ID = 200L;
    private static final Long AGENT_ACTOR_ID = 7L;
    private static final Long OTHER_ACTOR_ID = 8L;
    private static final Long PROJECT_ID = 42L;

    @Mock
    private WorkItemRepository workItemRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectActorService projectActorService;

    @Mock
    private RequestActorResolver requestActorResolver;

    /**
     * Added for WIKI4AI-150: {@code WorkItemService} now carries this collaborator
     * because the {@code done}-requires-a-{@code result}-comment invariant is decided in
     * the service. It is a constructor dependency, so without a mock here the service
     * built by {@code @InjectMocks} could not answer the invariant at all. Nothing else
     * in this class changes.
     */
    @Mock
    private WorkItemCommentRepository workItemCommentRepository;

    @InjectMocks
    private WorkItemService workItemService;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private User ownerUser() {
        return User.builder().id(OWNER_ID).username(OWNER)
                .email(OWNER + "@test.local").password("hash").role(Role.USER).build();
    }

    private User strangerUser() {
        return User.builder().id(STRANGER_ID).username(STRANGER)
                .email(STRANGER + "@test.local").password("hash").role(Role.USER).build();
    }

    private Project publicProject() {
        return Project.builder().id(PROJECT_ID).name("Public Project").slug("public-project")
                .visibility(Project.VISIBILITY_PUBLIC).owner(ownerUser()).build();
    }

    private Project privateProject() {
        return Project.builder().id(PROJECT_ID).name("Private Project").slug("private-project")
                .visibility(Project.VISIBILITY_PRIVATE).owner(ownerUser()).build();
    }

    private WorkItem item(Long id, String status) {
        return WorkItem.builder()
                .id(id)
                .projectId(PROJECT_ID)
                .title("Item " + id)
                .status(status)
                .priority("medium")
                .createdByUserId(OWNER_ID)
                .createdByActorId(AGENT_ACTOR_ID)
                .createdAt(LocalDateTime.of(2026, 10, 9, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 10, 9, 10, 0))
                .version(0)
                .build();
    }

    /** Read paths resolve only the user — they never touch attribution. */
    private void givenOwnerUser() {
        given(userRepository.findByUsername(OWNER)).willReturn(Optional.of(ownerUser()));
    }

    private void givenStrangerUser() {
        given(userRepository.findByUsername(STRANGER)).willReturn(Optional.of(strangerUser()));
    }

    /** Write paths additionally resolve the actor named by the credential. */
    private void givenOwnerCaller() {
        givenOwnerUser();
        given(requestActorResolver.currentAttributionActorId(OWNER)).willReturn(AGENT_ACTOR_ID);
    }

    private void givenStrangerCaller() {
        givenStrangerUser();
        given(requestActorResolver.currentAttributionActorId(STRANGER)).willReturn(OTHER_ACTOR_ID);
    }

    private void givenSaveEcho() {
        given(workItemRepository.save(any(WorkItem.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private WorkItemRequestDTO createDto() {
        return WorkItemRequestDTO.builder().projectId(PROJECT_ID).title("Implement claim").build();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Create (POST /api/v1/work-items)")
    class Create {

        @Test
        @DisplayName("projectId is required (400)")
        void projectIdIsRequired() {
            givenOwnerCaller();
            WorkItemRequestDTO dto = WorkItemRequestDTO.builder().title("No project").build();

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("projectId");
        }

        @Test
        @DisplayName("blank title is rejected (400)")
        void blankTitleIsRejected() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            WorkItemRequestDTO dto = WorkItemRequestDTO.builder().projectId(PROJECT_ID).title("   ").build();

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("title");
        }

        @Test
        @DisplayName("a new item defaults to status 'backlog' and priority 'medium'")
        void defaultsMatchTheMigration() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO created = workItemService.createWorkItem(createDto(), OWNER);

            assertThat(created.getStatus()).isEqualTo(WorkItem.STATUS_BACKLOG);
            assertThat(created.getPriority()).isEqualTo(WorkItem.DEFAULT_PRIORITY);
        }

        @Test
        @DisplayName("createdByActorId comes from the credential, createdByUserId from the user")
        void attributionComesFromTheCredential() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO created = workItemService.createWorkItem(createDto(), OWNER);

            assertThat(created.getCreatedByActorId()).isEqualTo(AGENT_ACTOR_ID);
            assertThat(created.getCreatedByUserId()).isEqualTo(OWNER_ID);
            assertThat(created.getUpdatedByActorId()).isNull();
        }

        @Test
        @DisplayName("status 'claimed' cannot be created (400) — only a claim produces it")
        void claimedIsNotCreatable() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            WorkItemRequestDTO dto = createDto();
            dto.setStatus(WorkItem.STATUS_CLAIMED);

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("claimed");
            verify(workItemRepository, never()).save(any(WorkItem.class));
        }

        @Test
        @DisplayName("an unknown status is 400, not a silent default")
        void unknownStatusIsRejected() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            WorkItemRequestDTO dto = createDto();
            dto.setStatus("in-progress");

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("in-progress");
        }

        @Test
        @DisplayName("an unknown priority is 400")
        void unknownPriorityIsRejected() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            WorkItemRequestDTO dto = createDto();
            dto.setPriority("whenever");

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("whenever");
        }

        @Test
        @DisplayName("a duplicate externalRef is 409 (IllegalArgumentException)")
        void duplicateExternalRefConflicts() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(workItemRepository.existsByExternalRef("WIKI4AI-140")).willReturn(true);

            WorkItemRequestDTO dto = createDto();
            dto.setExternalRef("WIKI4AI-140");

            assertThatThrownBy(() -> workItemService.createWorkItem(dto, OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WIKI4AI-140");
        }

        @Test
        @DisplayName("labels are encoded into one TEXT value and decoded back")
        void labelsRoundTrip() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemRequestDTO dto = createDto();
            dto.setLabels(List.of(" backend ", "api", "api", "  "));

            WorkItemDTO created = workItemService.createWorkItem(dto, OWNER);

            ArgumentCaptor<WorkItem> saved = ArgumentCaptor.forClass(WorkItem.class);
            verify(workItemRepository).save(saved.capture());
            assertThat(saved.getValue().getLabels())
                    .as("the entity stores one TEXT string, not a collection")
                    .isEqualTo("backend,api");
            assertThat(created.getLabels()).containsExactly("backend", "api");
        }

        @Test
        @DisplayName("a foreign private project is 404 — its existence is not revealed")
        void foreignPrivateProjectIsNotFound() {
            givenStrangerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(privateProject()));

            assertThatThrownBy(() -> workItemService.createWorkItem(createDto(), STRANGER))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("a foreign public project is 403 — it exists, but this caller may not write to it")
        void foreignPublicProjectIsForbidden() {
            givenStrangerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(projectActorService.hasAtLeast(PROJECT_ID, OTHER_ACTOR_ID, ProjectActor.ROLE_WORKER))
                    .willReturn(false);

            assertThatThrownBy(() -> workItemService.createWorkItem(createDto(), STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an actor with the project 'worker' role may create work items")
        void workerRoleMayCreate() {
            givenStrangerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(projectActorService.hasAtLeast(PROJECT_ID, OTHER_ACTOR_ID, ProjectActor.ROLE_WORKER))
                    .willReturn(true);
            givenSaveEcho();

            WorkItemDTO created = workItemService.createWorkItem(createDto(), STRANGER);

            assertThat(created.getProjectId()).isEqualTo(PROJECT_ID);
            assertThat(created.getCreatedByUserId()).isEqualTo(STRANGER_ID);
        }

        @Test
        @DisplayName("an anonymous caller may not write (403, never 500)")
        void anonymousCallerIsRejected() {
            assertThatThrownBy(() -> workItemService.createWorkItem(createDto(), "anonymous"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ── State machine ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("State machine (PUT /api/v1/work-items/{id})")
    class StateMachine {

        /** Stub everything an update needs up to the point of writing. */
        private void givenItemIn(String status) {
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, status)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
        }

        private WorkItemDTO update(String status, String requested) {
            givenItemIn(status);
            givenSaveEcho();
            return workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status(requested).build(), OWNER);
        }

        private void expectConflict(String from, String to) {
            givenItemIn(from);
            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status(to).build(), OWNER))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(workItemRepository, never()).save(any(WorkItem.class));
        }

        @Test
        @DisplayName("backlog -> ready is legal")
        void backlogToReady() {
            assertThat(update(WorkItem.STATUS_BACKLOG, WorkItem.STATUS_READY).getStatus())
                    .isEqualTo(WorkItem.STATUS_READY);
        }

        @Test
        @DisplayName("running -> review is legal")
        void runningToReview() {
            assertThat(update(WorkItem.STATUS_RUNNING, WorkItem.STATUS_REVIEW).getStatus())
                    .isEqualTo(WorkItem.STATUS_REVIEW);
        }

        @Test
        @DisplayName("review -> done is legal")
        void reviewToDone() {
            // WIKI4AI-150 changed the contract this test exercises: 'done' is now
            // reachable only when the item already carries a comment of kind 'result'.
            // The assertion — review -> done is a legal transition — is unchanged; what
            // changed is that the test must now satisfy the new precondition, so the
            // lookup the service performs answers "yes, there is one".
            given(workItemCommentRepository.existsByWorkItemIdAndKind(1L, WorkItemComment.KIND_RESULT))
                    .willReturn(true);
            assertThat(update(WorkItem.STATUS_REVIEW, WorkItem.STATUS_DONE).getStatus())
                    .isEqualTo(WorkItem.STATUS_DONE);
        }

        @Test
        @DisplayName("blocked and cancelled are reachable from a live state")
        void blockedAndCancelledAreReachable() {
            assertThat(update(WorkItem.STATUS_READY, WorkItem.STATUS_BLOCKED).getStatus())
                    .isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(update(WorkItem.STATUS_RUNNING, WorkItem.STATUS_CANCELLED).getStatus())
                    .isEqualTo(WorkItem.STATUS_CANCELLED);
        }

        @Test
        @DisplayName("backlog -> done is an illegal transition (409)")
        void backlogToDoneConflicts() {
            givenItemIn(WorkItem.STATUS_BACKLOG);
            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_DONE).build(), OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("backlog")
                    .hasMessageContaining("done");
        }

        @Test
        @DisplayName("'done' is terminal (409)")
        void doneIsTerminal() {
            expectConflict(WorkItem.STATUS_DONE, WorkItem.STATUS_RUNNING);
        }

        @Test
        @DisplayName("'cancelled' is terminal (409)")
        void cancelledIsTerminal() {
            expectConflict(WorkItem.STATUS_CANCELLED, WorkItem.STATUS_BACKLOG);
        }

        @Test
        @DisplayName("keeping the current status is a no-op, not a conflict")
        void sameStatusIsAllowed() {
            assertThat(update(WorkItem.STATUS_READY, WorkItem.STATUS_READY).getStatus())
                    .isEqualTo(WorkItem.STATUS_READY);
        }

        @Test
        @DisplayName("AC: 'claimed' is NOT reachable through a plain update (409)")
        void claimedIsUnreachableByUpdate() {
            assertThatThrownBy(() -> {
                givenItemIn(WorkItem.STATUS_READY);
                workItemService.updateWorkItem(1L,
                        WorkItemRequestDTO.builder().status(WorkItem.STATUS_CLAIMED).build(), OWNER);
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/claim");

            assertThatThrownBy(() -> {
                givenItemIn(WorkItem.STATUS_BACKLOG);
                workItemService.updateWorkItem(1L,
                        WorkItemRequestDTO.builder().status(WorkItem.STATUS_CLAIMED).build(), OWNER);
            }).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> {
                givenItemIn(WorkItem.STATUS_RUNNING);
                workItemService.updateWorkItem(1L,
                        WorkItemRequestDTO.builder().status(WorkItem.STATUS_CLAIMED).build(), OWNER);
            }).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("claimed -> running keeps the lease")
        void claimedToRunningKeepsLease() {
            WorkItem held = item(1L, WorkItem.STATUS_CLAIMED);
            held.setAssigneeActorId(AGENT_ACTOR_ID);
            held.setClaimToken("token-1");
            held.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(held));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO running = workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_RUNNING).build(), OWNER);

            assertThat(running.getStatus()).isEqualTo(WorkItem.STATUS_RUNNING);
            assertThat(running.getAssigneeActorId()).isEqualTo(AGENT_ACTOR_ID);
            assertThat(held.getClaimToken())
                    .as("the lease survives the transition: claimed -> running keeps the claim, "
                            + "its token and its expiry on the row")
                    .isEqualTo("token-1");
            assertThat(running.getLeaseExpiresAt())
                    .as("and the read still says when the lease ends — that is the half of the "
                            + "lease the board renders")
                    .isNotNull();
            // WIKI4AI-177: this assertion used to read `running.getClaimToken()`. The
            // token is no longer part of what a read reports — WorkItemDTO is the read
            // DTO and only WorkItemClaimDTO carries the token. The rule this test exists
            // for (the lease is kept) is now asserted on the entity above; this line is
            // the regression guard that it stays off the DTO.
            assertThat(running.getClaimToken())
                    .as("WIKI4AI-177: a status transition answers with WorkItemDTO, and that "
                            + "DTO no longer carries the claim token")
                    .isNull();
        }

        @Test
        @DisplayName("claimed -> blocked releases the lease")
        void claimedToBlockedReleasesLease() {
            WorkItem held = item(1L, WorkItem.STATUS_CLAIMED);
            held.setAssigneeActorId(AGENT_ACTOR_ID);
            held.setClaimToken("token-1");
            held.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(held));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO released = workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status(WorkItem.STATUS_BLOCKED).build(), OWNER);

            assertThat(released.getStatus()).isEqualTo(WorkItem.STATUS_BLOCKED);
            assertThat(released.getAssigneeActorId())
                    .as("a released item must not keep a stale lease")
                    .isNull();
            assertThat(released.getClaimToken()).isNull();
            assertThat(released.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("an update is attributed to the caller's actor")
        void updateIsAttributed() {
            givenItemIn(WorkItem.STATUS_BACKLOG);
            givenSaveEcho();

            WorkItemDTO updated = workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().title("Renamed").build(), OWNER);

            assertThat(updated.getUpdatedByActorId()).isEqualTo(AGENT_ACTOR_ID);
            assertThat(updated.getCreatedByActorId())
                    .as("the creating actor is never rewritten")
                    .isEqualTo(AGENT_ACTOR_ID);
        }

        @Test
        @DisplayName("PATCH semantics: null labels keep them")
        void nullLabelsAreKept() {
            WorkItem labelled = item(1L, WorkItem.STATUS_BACKLOG);
            labelled.setLabels("backend,api");
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(labelled));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO kept = workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().title("Same labels").build(), OWNER);

            assertThat(kept.getLabels()).containsExactly("backend", "api");
        }

        @Test
        @DisplayName("PATCH semantics: an empty label list clears them")
        void emptyLabelsAreCleared() {
            WorkItem labelled = item(1L, WorkItem.STATUS_BACKLOG);
            labelled.setLabels("backend,api");
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(labelled));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            givenSaveEcho();

            WorkItemDTO cleared = workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().labels(List.of()).build(), OWNER);

            assertThat(cleared.getLabels()).isEmpty();
        }

        @Test
        @DisplayName("an unknown status value is 400, not 409")
        void unknownStatusIsBadRequest() {
            givenItemIn(WorkItem.STATUS_BACKLOG);

            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().status("nope").build(), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // ── Parent / external ref on update ───────────────────────────────────────

    @Nested
    @DisplayName("Parent and externalRef")
    class ParentAndExternalRef {

        @Test
        @DisplayName("a parent from another project is rejected (400)")
        void parentMustBeInTheSameProject() {
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_BACKLOG)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            WorkItem foreignParent = WorkItem.builder().id(9L).projectId(99L).title("Other project")
                    .status(WorkItem.STATUS_BACKLOG).priority("medium").build();
            given(workItemRepository.findById(9L)).willReturn(Optional.of(foreignParent));

            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().parentId(9L).build(), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("same project");
        }

        @Test
        @DisplayName("an item cannot be its own parent (400)")
        void selfParentIsRejected() {
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_BACKLOG)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().parentId(1L).build(), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("own parent");
        }

        @Test
        @DisplayName("a parent that is the item's own descendant would close a cycle (400)")
        void cycleIsRejected() {
            // Today: 1 is the root, 2 is its child, 3 is 2's child. Asking to put 1
            // under 3 would make the root a child of its own grandchild.
            WorkItem root = item(1L, WorkItem.STATUS_BACKLOG);
            WorkItem child = WorkItem.builder().id(2L).projectId(PROJECT_ID).title("Child")
                    .status(WorkItem.STATUS_BACKLOG).priority("medium").parentId(1L).build();
            WorkItem grandchild = WorkItem.builder().id(3L).projectId(PROJECT_ID).title("Grandchild")
                    .status(WorkItem.STATUS_BACKLOG).priority("medium").parentId(2L).build();
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(root));
            given(workItemRepository.findById(3L)).willReturn(Optional.of(grandchild));
            given(workItemRepository.findById(2L)).willReturn(Optional.of(child));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));

            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().parentId(3L).build(), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        @DisplayName("an externalRef already used by another item is 409")
        void externalRefTakenByAnotherItemConflicts() {
            WorkItem mine = item(1L, WorkItem.STATUS_BACKLOG);
            mine.setExternalRef("MINE");
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(mine));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(workItemRepository.existsByExternalRefAndIdNot("THEIRS", 1L)).willReturn(true);

            assertThatThrownBy(() -> workItemService.updateWorkItem(1L,
                    WorkItemRequestDTO.builder().externalRef("THEIRS").build(), OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("THEIRS");
        }
    }

    // ── Ownership on read / delete / list ─────────────────────────────────────

    @Nested
    @DisplayName("Ownership on read, delete and list")
    class Ownership {

        @Test
        @DisplayName("reading an item of a foreign private project is 404")
        void readOfForeignPrivateProjectIsNotFound() {
            givenStrangerUser();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_BACKLOG)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(privateProject()));

            assertThatThrownBy(() -> workItemService.getWorkItem(1L, STRANGER))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("deleting an item of a foreign public project is 403")
        void deleteOfForeignPublicProjectIsForbidden() {
            givenStrangerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_BACKLOG)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(projectActorService.hasAtLeast(PROJECT_ID, OTHER_ACTOR_ID, ProjectActor.ROLE_WORKER))
                    .willReturn(false);

            assertThatThrownBy(() -> workItemService.deleteWorkItem(1L, STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(workItemRepository, never()).delete(any(WorkItem.class));
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIdIsNotFound() {
            givenOwnerUser();
            given(workItemRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> workItemService.getWorkItem(999L, OWNER))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("an unknown status filter is 400 instead of an empty list")
        void unknownStatusFilterIsBadRequest() {
            givenOwnerUser();

            assertThatThrownBy(() -> workItemService.listWorkItems(PROJECT_ID, "done-ish", OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("a list of a foreign private project is 404")
        void listOfForeignPrivateProjectIsNotFound() {
            givenStrangerUser();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(privateProject()));

            assertThatThrownBy(() -> workItemService.listWorkItems(PROJECT_ID, null, STRANGER))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("a list without projectId drops items of invisible private projects")
        void listWithoutProjectIdHidesPrivateProjects() {
            givenStrangerUser();
            given(workItemRepository.findAllByOrderByCreatedAtDesc())
                    .willReturn(List.of(item(1L, WorkItem.STATUS_BACKLOG), item(2L, WorkItem.STATUS_READY)));
            given(projectRepository.findAllById(any())).willReturn(List.of(privateProject()));

            List<WorkItemDTO> visible = workItemService.listWorkItems(null, null, STRANGER);

            assertThat(visible).isEmpty();
        }
    }

    // ── Claim ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Claim (WIKI4AI-141)")
    class Claim {

        private WorkItem claimedItem() {
            WorkItem claimed = WorkItem.builder()
                    .id(1L)
                    .projectId(PROJECT_ID)
                    .title("Item 1")
                    .status(WorkItem.STATUS_CLAIMED)
                    .priority("medium")
                    .createdByUserId(OWNER_ID)
                    .createdByActorId(AGENT_ACTOR_ID)
                    .assigneeActorId(AGENT_ACTOR_ID)
                    .claimToken("c".repeat(64))
                    .leaseExpiresAt(LocalDateTime.now().plusMinutes(15))
                    .createdAt(LocalDateTime.of(2026, 10, 9, 10, 0))
                    .updatedAt(LocalDateTime.of(2026, 10, 9, 10, 0))
                    // version 1: the claim UPDATE already incremented it
                    .version(1)
                    .build();
            return claimed;
        }

        @Test
        @DisplayName("a caller whose credential names no actor is rejected with 403, not 500")
        void claimWithoutActorIsForbidden() {
            given(requestActorResolver.currentAttributionActorId(STRANGER)).willReturn(null);

            assertThatThrownBy(() -> workItemService.claimWorkItem(1L, STRANGER))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("actor");

            verify(workItemRepository, never()).claim(anyLong(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("0 rows updated is 409 — the item was not 'ready'")
        void claimOfNotReadyItemConflicts() {
            givenOwnerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(workItemRepository.claim(eq(1L), eq(AGENT_ACTOR_ID), any(LocalDateTime.class), anyString()))
                    .willReturn(0);

            assertThatThrownBy(() -> workItemService.claimWorkItem(1L, OWNER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ready");
            verify(workItemRepository, never()).save(any(WorkItem.class));
        }

        @Test
        @DisplayName("a successful claim returns the token, the lease and the assignee")
        void successfulClaimReturnsTheClaimTriple() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            // First read: the ready item. Second read (after clearAutomatically): the claimed row.
            given(workItemRepository.findById(1L))
                    .willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)), Optional.of(claimedItem()));
            given(workItemRepository.claim(eq(1L), eq(AGENT_ACTOR_ID), any(LocalDateTime.class), anyString()))
                    .willReturn(1);
            givenSaveEcho();

            WorkItemClaimDTO claim = workItemService.claimWorkItem(1L, OWNER);

            assertThat(claim.getStatus()).isEqualTo(WorkItem.STATUS_CLAIMED);
            assertThat(claim.getAssigneeActorId()).isEqualTo(AGENT_ACTOR_ID);
            assertThat(claim.getClaimToken()).isEqualTo("c".repeat(64));
            assertThat(claim.getLeaseExpiresAt()).isAfter(LocalDateTime.now());
            assertThat(claim.getVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("the lease is a LocalDateTime in the future and the token is 64 hex chars")
        void leaseTypeAndTokenShape() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(workItemRepository.findById(1L))
                    .willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)), Optional.of(claimedItem()));
            ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
            given(workItemRepository.claim(eq(1L), eq(AGENT_ACTOR_ID), lease.capture(), token.capture()))
                    .willReturn(1);
            givenSaveEcho();

            workItemService.claimWorkItem(1L, OWNER);

            assertThat(lease.getValue())
                    .as("SPEC rule 11b: the lease travels as LocalDateTime, never as Instant")
                    .isInstanceOf(LocalDateTime.class)
                    .isAfter(LocalDateTime.now());
            assertThat(token.getValue())
                    .as("claim_token is VARCHAR(64)")
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("the claim is attributed to the claiming actor")
        void claimIsAttributed() {
            givenOwnerCaller();
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(workItemRepository.findById(1L))
                    .willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)), Optional.of(claimedItem()));
            given(workItemRepository.claim(eq(1L), eq(AGENT_ACTOR_ID), any(LocalDateTime.class), anyString()))
                    .willReturn(1);
            givenSaveEcho();

            workItemService.claimWorkItem(1L, OWNER);

            ArgumentCaptor<WorkItem> saved = ArgumentCaptor.forClass(WorkItem.class);
            verify(workItemRepository).save(saved.capture());
            assertThat(saved.getValue().getUpdatedByActorId()).isEqualTo(AGENT_ACTOR_ID);
        }

        @Test
        @DisplayName("claiming an item of a foreign private project is 404 before anything is written")
        void claimOfForeignPrivateProjectIsNotFound() {
            givenStrangerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(privateProject()));

            assertThatThrownBy(() -> workItemService.claimWorkItem(1L, STRANGER))
                    .isInstanceOf(EntityNotFoundException.class);
            verify(workItemRepository, never()).claim(anyLong(), anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("claiming an item of a foreign public project is 403")
        void claimOfForeignPublicProjectIsForbidden() {
            givenStrangerCaller();
            given(workItemRepository.findById(1L)).willReturn(Optional.of(item(1L, WorkItem.STATUS_READY)));
            given(projectRepository.findById(PROJECT_ID)).willReturn(Optional.of(publicProject()));
            given(projectActorService.hasAtLeast(PROJECT_ID, OTHER_ACTOR_ID, ProjectActor.ROLE_WORKER))
                    .willReturn(false);

            assertThatThrownBy(() -> workItemService.claimWorkItem(1L, STRANGER))
                    .isInstanceOf(AccessDeniedException.class);
            verify(workItemRepository, never()).claim(anyLong(), anyLong(), any(), anyString());
        }
    }

    // ── The request shape is the security rule ────────────────────────────────

    @Nested
    @DisplayName("A request body cannot name an actor")
    class RequestShape {

        @Test
        @DisplayName("WorkItemRequestDTO declares no attribution, claim or version field")
        void requestDtoCarriesNoActorOrClaimFields() {
            List<String> forbidden = List.of(
                    "createdByActorId", "updatedByActorId", "assigneeActorId",
                    "claimToken", "leaseExpiresAt", "version", "createdByUserId");
            List<String> declared = Arrays.stream(WorkItemRequestDTO.class.getDeclaredFields())
                    .map(Field::getName)
                    .toList();

            for (String name : forbidden) {
                assertThat(declared)
                        .as("a body field named '%s' would let a client name an actor or forge a lease", name)
                        .doesNotContain(name);
            }
        }
    }
}
