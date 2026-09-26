package com.wiki4ai.repository;

import com.wiki4ai.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository interface for CalendarEvent entity (WIKI4AI-96).
 * Provides CRUD operations and custom queries for calendar events.
 */
@Repository
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    /**
     * Find all events within a date range (inclusive), regardless of visibility.
     * Used for ADMIN users who see everything.
     *
     * @param from start of the range (inclusive)
     * @param to   end of the range (inclusive)
     * @return list of events in the range
     */
    List<CalendarEvent> findByEventDateBetween(LocalDate from, LocalDate to);

    /**
     * Find events visible to a given user within a date range (inclusive):
     * all public events plus the user's own private events. A null userId
     * (unauthenticated caller) sees only public events.
     *
     * @param from   start of the range (inclusive)
     * @param to     end of the range (inclusive)
     * @param userId id of the requesting user, or null for anonymous
     * @return list of visible events in the range
     */
    @Query("select e from CalendarEvent e where e.eventDate between :from and :to " +
            "and (e.visibility = 'public' or e.creator.id = :userId)")
    List<CalendarEvent> findVisibleEvents(@Param("from") LocalDate from,
                                          @Param("to") LocalDate to,
                                          @Param("userId") Long userId);

    /**
     * Find all events of a given creator within a date range (inclusive),
     * regardless of visibility. Used for the mine=true filter.
     *
     * @param creatorId id of the event creator
     * @param from      start of the range (inclusive)
     * @param to        end of the range (inclusive)
     * @return list of the creator's events in the range
     */
    List<CalendarEvent> findByCreatorIdAndEventDateBetween(Long creatorId, LocalDate from, LocalDate to);

    /**
     * Count events referencing a given event type. Used to reject deletion of
     * a type that is still in use (409).
     *
     * @param eventTypeId id of the event type
     * @return number of events using the type
     */
    long countByEventTypeId(Long eventTypeId);
}
