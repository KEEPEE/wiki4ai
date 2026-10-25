package com.wiki4ai.controller;

import com.wiki4ai.exception.BadRequestException;
import com.wiki4ai.exception.CommentContractException;
import com.wiki4ai.exception.RateLimitExceededException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-149 added one status to {@code GlobalExceptionHandler}: 422, for
 * {@link CommentContractException}. This test is the proof that the addition is an
 * addition.
 * <p>
 * It calls the advice's own handler methods, with no Spring context and no MockMvc, so
 * what it measures is the mapping itself: which exception class produces which status.
 * Every mapping that existed before this story is asserted at the status it had before
 * this story — including the two the story was told not to touch:
 * <ul>
 *   <li>{@code IllegalArgumentException → 409}, the mapping the work_items stories
 *   depend on for a duplicate external_ref, a lost claim race and an illegal
 *   transition. Reusing it for a contract violation would have been the easy way to
 *   get a 422 and would have broken those tests;</li>
 *   <li>{@code MethodArgumentNotValidException → 400}, which cannot be repurposed
 *   either: it answers bean-validation failures, and it answers them with 400.</li>
 * </ul>
 * The second half asserts the property that makes the first half safe: 422 is reachable
 * from exactly one exception class, and that class is new.
 */
@DisplayName("GlobalExceptionHandler: the 422 is added, no existing mapping moved (WIKI4AI-149)")
class GlobalExceptionHandlerStatusMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static int statusOf(ResponseEntity<Map<String, Object>> response) {
        return response.getStatusCode().value();
    }

    @Nested
    @DisplayName("every mapping that existed before WIKI4AI-149 still answers what it answered")
    class ExistingMappingsAreUnchanged {

        @Test
        @DisplayName("IllegalArgumentException → 409 (the mapping this story must not touch)")
        void illegalArgumentIsStill409() {
            assertThat(statusOf(handler.handleIllegalArgument(
                    new IllegalArgumentException("A work item with external reference 'X' already exists"))))
                    .isEqualTo(HttpStatus.CONFLICT.value());
        }

        @Test
        @DisplayName("IllegalArgumentException keeps its two other branches too")
        void illegalArgumentKeepsItsMessageBasedBranches() {
            assertThat(statusOf(handler.handleIllegalArgument(
                    new IllegalArgumentException("This vault entry does not belong to you"))))
                    .as("ownership branch → 403")
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(statusOf(handler.handleIllegalArgument(
                    new IllegalArgumentException("Invalid file format"))))
                    .as("validation branch → 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("MethodArgumentNotValidException → 400, not 422")
        void beanValidationIsStill400() throws Exception {
            Method method = SampleBody.class.getDeclaredMethod("body", String.class);
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new SampleBody(), "sample");
            binding.addError(new FieldError("sample", "body", "must not be blank"));

            assertThat(statusOf(handler.handleValidationExceptions(
                    new MethodArgumentNotValidException(new MethodParameter(method, 0), binding))))
                    .as("bean validation cannot be repurposed into a 422")
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("BadRequestException → 400")
        void badRequestIsStill400() {
            assertThat(statusOf(handler.handleBadRequest(new BadRequestException("Comment body is required"))))
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("EntityNotFoundException → 404")
        void entityNotFoundIsStill404() {
            assertThat(statusOf(handler.handleEntityNotFound(
                    new EntityNotFoundException("Work item not found with id: 42"))))
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        @Test
        @DisplayName("AccessDeniedException → 403")
        void accessDeniedIsStill403() {
            assertThat(statusOf(handler.handleAccessDenied(new AccessDeniedException("Authentication required"))))
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        @DisplayName("HttpRequestMethodNotSupportedException → 405")
        void methodNotSupportedIsStill405() {
            assertThat(statusOf(handler.handleMethodNotSupported(
                    new HttpRequestMethodNotSupportedException("PUT"))))
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        }

        @Test
        @DisplayName("RateLimitExceededException → 429")
        void rateLimitIsStill429() {
            assertThat(statusOf(handler.handleRateLimitExceeded(new RateLimitExceededException("too fast"))))
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        @Test
        @DisplayName("an unhandled exception is still the 500 catch-all")
        void catchAllIsStill500() {
            assertThat(statusOf(handler.handleGeneralException(new IllegalStateException("unexpected"))))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }

        /** Only needed to build a real MethodArgumentNotValidException. */
        private static class SampleBody {
            @SuppressWarnings("unused")
            void body(String value) {
            }
        }
    }

    @Nested
    @DisplayName("422 is new, and reachable from exactly one exception class")
    class TheNewStatus {

        @Test
        @DisplayName("CommentContractException → 422 with kind and violations in the body")
        void commentContractIs422() {
            ResponseEntity<Map<String, Object>> response = handler.handleCommentContract(
                    new CommentContractException("result", List.of("'where' is required",
                            "'artifacts' must be an array")));

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody())
                    .isNotNull()
                    .containsEntry("status", 422)
                    .containsEntry("error", "Unprocessable Entity")
                    .containsEntry("kind", "result");
            assertThat(response.getBody().get("violations"))
                    .as("the caller is told which fields to fix")
                    .isInstanceOf(List.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .hasSize(2);
        }

        @Test
        @DisplayName("CommentContractException is not a subclass of anything the old handlers answer")
        void theNewExceptionCannotReachAnOldHandler() {
            assertThat(IllegalArgumentException.class.isAssignableFrom(CommentContractException.class))
                    .as("otherwise the 409 mapping would swallow it")
                    .isFalse();
            assertThat(BadRequestException.class.isAssignableFrom(CommentContractException.class))
                    .as("otherwise the 400 mapping would swallow it")
                    .isFalse();
            assertThat(RuntimeException.class.isAssignableFrom(CommentContractException.class))
                    .isTrue();
        }

        @Test
        @DisplayName("the advice declares exactly one handler, and it is routed by the new exception")
        void onlyOneHandlerIsRoutedToTheNewException() {
            List<String> routedToCommentContract = java.util.Arrays.stream(
                            GlobalExceptionHandler.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(
                            org.springframework.web.bind.annotation.ExceptionHandler.class))
                    .filter(this::isRoutedToCommentContract)
                    .map(Method::getName)
                    .toList();

            assertThat(routedToCommentContract).containsExactly("handleCommentContract");
        }

        /**
         * Routing is decided by the annotation's exception type, so that is what this
         * checks — not a string search for "422" in a source file.
         */
        private boolean isRoutedToCommentContract(Method method) {
            org.springframework.web.bind.annotation.ExceptionHandler annotation =
                    method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (annotation == null) {
                return false;
            }
            for (Class<? extends Throwable> type : annotation.value()) {
                if (type == CommentContractException.class) {
                    return true;
                }
            }
            return false;
        }
    }
}
