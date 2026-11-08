package dev.juniordev.flags;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Bounded, in-memory administrative audit journal.
 *
 * Only operation names, counts and a short sample of flag names are stored.
 * Tokens, subjects, request bodies and other personal information are never logged.
 * This journal is volatile and is not a substitute for durable compliance auditing.
 */
final class FlagAuditLog {
    static final int CAPACITY = 256;
    static final int SAMPLE_LIMIT = 12;
    static final int MAX_PAGE_SIZE = 100;

    record Event(long id, Instant occurredAt, String operation,
                 int affected, List<String> sampleNames) {
        Event {
            sampleNames = List.copyOf(sampleNames);
        }
    }

    record Page(List<Event> events, long nextAfter, boolean hasMore,
                long oldestAvailable) {
        Page {
            events = List.copyOf(events);
        }
    }

    private final Clock clock;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;

    FlagAuditLog() {
        this(Clock.systemUTC());
    }

    FlagAuditLog(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * Record one successful administrative mutation. This method deliberately
     * does not accept a subject or an admin token, preventing accidental leakage.
     */
    synchronized Event record(String operation, Collection<String> names) {
        if (operation == null || !operation.matches("[a-z][a-z-]{0,39}"))
            throw new IllegalArgumentException("Invalid audit operation");
        if (names == null || names.isEmpty())
            throw new IllegalArgumentException("Audit event requires affected flags");

        var unique = new TreeSet<String>();
        for (String name : names) {
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("Invalid audited flag name");
            unique.add(name);
        }
        var sample = new ArrayList<String>(SAMPLE_LIMIT);
        for (String name : unique) {
            if (sample.size() == SAMPLE_LIMIT) break;
            sample.add(name);
        }

        // Every event is immutable. The monotonically increasing sequence is
        // independent of retention and never reused after eviction.
        var event = new Event(++sequence, Instant.now(clock), operation,
                unique.size(), sample);
        events.addLast(event);
        if (events.size() > CAPACITY) events.removeFirst();
        return event;
    }

    synchronized Optional<Event> find(long id) {
        if (id <= 0) return Optional.empty();
        for (Event event : events) {
            if (event.id() == id) return Optional.of(event);
        }
        return Optional.empty();
    }

    /**
     * Cursor pagination is stable under retention: a client can advance using
     * nextAfter even when earlier events are evicted. oldestAvailable lets the
     * client detect a gap and resynchronize its local journal.
     */
    synchronized Page page(long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Invalid audit cursor or limit");
        var selected = new ArrayList<Event>(Math.min(limit, events.size()));
        boolean hasMore = false;
        for (Event event : events) {
            if (event.id() <= after) continue;
            if (selected.size() == limit) {
                hasMore = true;
                break;
            }
            selected.add(event);
        }
        long nextAfter = selected.isEmpty() ? after : selected.get(selected.size() - 1).id();
        long oldestAvailable = events.isEmpty() ? 0 : events.getFirst().id();
        return new Page(selected, nextAfter, hasMore, oldestAvailable);
    }

    synchronized int size() {
        return events.size();
    }
}
