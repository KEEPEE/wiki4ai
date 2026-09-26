package com.wiki4ai.repository;

import com.wiki4ai.model.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for EventType entity (WIKI4AI-96).
 * Provides CRUD operations and custom queries for calendar event types.
 */
@Repository
public interface EventTypeRepository extends JpaRepository<EventType, Long> {

    /**
     * Find an event type by name, case-insensitively.
     *
     * @param name the type name to look up
     * @return Optional containing the event type if found
     */
    Optional<EventType> findByNameIgnoreCase(String name);

    /**
     * Check whether an event type with the given name already exists (case-insensitive).
     *
     * @param name the type name to check
     * @return true if a type with this name exists
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Find all event types ordered by id (stable: seeds first, then user-created).
     *
     * @return list of all event types
     */
    List<EventType> findAllByOrderByIdAsc();
}
