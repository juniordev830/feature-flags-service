package dev.juniordev.flags;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FlagAuditLogTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private FlagAuditLog journal() {
        return new FlagAuditLog(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void emptyJournalHasNoCursorOrHistory() {
        var log = journal();
        var page = log.page(0, 10);
        assertTrue(page.events().isEmpty());
        assertEquals(0, page.nextAfter());
        assertEquals(0, page.oldestAvailable());
        assertFalse(page.hasMore());
        assertEquals(0, log.size());
    }

    @Test
    void recordsTimestampOperationAndAffectedCount() {
        var log = journal();
        var event = log.record("update", List.of("checkout.v2"));
        assertEquals(1, event.id());
        assertEquals(NOW, event.occurredAt());
        assertEquals("update", event.operation());
        assertEquals(1, event.affected());
        assertEquals(List.of("checkout.v2"), event.sampleNames());
        assertEquals(event, log.find(1).orElseThrow());
    }

    @Test
    void samplesAreSortedAndDeduplicated() {
        var log = journal();
        var event = log.record("bulk-update", List.of("z", "b", "a", "b"));
        assertEquals(3, event.affected());
        assertEquals(List.of("a", "b", "z"), event.sampleNames());
    }

    @Test
    void sampleLimitDoesNotTruncateAffectedCount() {
        var log = journal();
        var names = new ArrayList<String>();
        for (int i = 0; i < 30; i++) names.add("flag." + i);
        var event = log.record("snapshot-import", names);
        assertEquals(30, event.affected());
        assertEquals(FlagAuditLog.SAMPLE_LIMIT, event.sampleNames().size());
        assertEquals("flag.0", event.sampleNames().get(0));
    }

    @Test
    void pagesAdvanceByEventIdWithoutDuplicates() {
        var log = journal();
        for (int i = 0; i < 7; i++)
            log.record("update", List.of("flag." + i));
        var first = log.page(0, 3);
        assertEquals(List.of(1L, 2L, 3L),
                first.events().stream().map(FlagAuditLog.Event::id).toList());
        assertEquals(3, first.nextAfter());
        assertTrue(first.hasMore());

        var second = log.page(first.nextAfter(), 3);
        assertEquals(List.of(4L, 5L, 6L),
                second.events().stream().map(FlagAuditLog.Event::id).toList());
        assertEquals(6, second.nextAfter());
        assertTrue(second.hasMore());

        var third = log.page(second.nextAfter(), 3);
        assertEquals(List.of(7L),
                third.events().stream().map(FlagAuditLog.Event::id).toList());
        assertFalse(third.hasMore());
        assertEquals(7, third.nextAfter());
    }

    @Test
    void cursorBeyondLatestReturnsEmptyWithoutMovingBackwards() {
        var log = journal();
        log.record("update", List.of("flag.a"));
        var page = log.page(999, 5);
        assertTrue(page.events().isEmpty());
        assertEquals(999, page.nextAfter());
        assertFalse(page.hasMore());
    }

    @Test
    void boundedRetentionEvictsOldestEvents() {
        var log = journal();
        for (int i = 0; i < FlagAuditLog.CAPACITY + 9; i++)
            log.record("update", List.of("flag." + i));
        assertEquals(FlagAuditLog.CAPACITY, log.size());
        assertTrue(log.find(1).isEmpty());
        assertTrue(log.find(9).isEmpty());
        assertEquals(10, log.page(0, 1).oldestAvailable());
        assertEquals(10, log.page(0, 1).events().get(0).id());
        assertEquals(FlagAuditLog.CAPACITY + 9, log.page(0, 100)
                .events().get(99).id() + (FlagAuditLog.CAPACITY - 100));
    }

    @Test
    void eventIdsRemainMonotonicAfterEviction() {
        var log = journal();
        for (int i = 0; i < 300; i++) log.record("update", List.of("f"));
        var event = log.record("delete", List.of("f"));
        assertEquals(301, event.id());
        assertEquals(46, log.page(0, 1).oldestAvailable());
    }

    @Test
    void findDoesNotExposeEvictedOrInvalidIds() {
        var log = journal();
        assertTrue(log.find(-1).isEmpty());
        assertTrue(log.find(0).isEmpty());
        assertTrue(log.find(10).isEmpty());
        log.record("update", List.of("a"));
        assertTrue(log.find(2).isEmpty());
        assertTrue(log.find(1).isPresent());
    }

    @Test
    void rejectsInvalidPageParameters() {
        var log = journal();
        assertThrows(IllegalArgumentException.class, () -> log.page(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> log.page(0, 0));
        assertThrows(IllegalArgumentException.class, () -> log.page(0, 101));
        assertDoesNotThrow(() -> log.page(0, 100));
    }

    @Test
    void rejectsInvalidOperationsAndNames() {
        var log = journal();
        assertThrows(IllegalArgumentException.class, () -> log.record(null, List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> log.record("UPPER", List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> log.record("update", null));
        assertThrows(IllegalArgumentException.class, () -> log.record("update", List.of()));
        assertThrows(IllegalArgumentException.class, () -> log.record("update", List.of(" ")));
        assertEquals(0, log.size());
    }

    @Test
    void snapshotsCannotBeMutatedByCallers() {
        var log = journal();
        var event = log.record("update", List.of("a", "b"));
        assertThrows(UnsupportedOperationException.class,
                () -> event.sampleNames().add("c"));
        var page = log.page(0, 10);
        assertThrows(UnsupportedOperationException.class,
                () -> page.events().clear());
        assertEquals(1, log.size());
    }

    @Test
    void mutationOfInputCollectionCannotChangeStoredEvent() {
        var log = journal();
        var names = new ArrayList<>(List.of("a", "b"));
        var event = log.record("bulk-update", names);
        names.clear();
        assertEquals(List.of("a", "b"), event.sampleNames());
        assertEquals(2, event.affected());
    }

    @Test
    void exactPageSizeDoesNotReportAdditionalResults() {
        var log = journal();
        for (int i = 0; i < 3; i++) log.record("update", List.of("a"));
        var page = log.page(0, 3);
        assertEquals(3, page.events().size());
        assertFalse(page.hasMore());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentWritersProduceUniqueOrderedIds() throws Exception {
        var log = journal();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 150; i++) {
                final int index = i;
                tasks.add(() -> log.record("update", List.of("flag." + index)).id());
            }
            var ids = new java.util.HashSet<Long>();
            for (var future : executor.invokeAll(tasks)) ids.add(future.get());
            assertEquals(150, ids.size());
            assertEquals(150, log.size());
            assertEquals(1, log.page(0, 1).events().get(0).id());
            assertEquals(150, log.page(149, 1).events().get(0).id());
        }
    }
}
