package ma.nuzul.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.catalog.HotelSnapshots;
import ma.nuzul.inventory.MinPrices;
import ma.nuzul.shared.error.ConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Story 3.2: full reindex behind the alias, one at a time, with a catch-up pass for edits made while it ran. */
class ReindexServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Africa/Casablanca"));
    /** The database clock, deliberately off the app clock: the catch-up window must follow the outbox's clock. */
    private static final Instant DB_NOW = NOW.plusSeconds(37);

    private static final String NEW_INDEX = "hotels-20261126-100000-000";

    private final ReindexLockPort lock = mock(ReindexLockPort.class);
    private final HotelSnapshots hotels = mock(HotelSnapshots.class);
    private final MinPrices minPrices = mock(MinPrices.class);
    private final HotelSearchIndex index = mock(HotelSearchIndex.class);
    private final RecentCatalogChangesPort changes = mock(RecentCatalogChangesPort.class);
    private final HotelDocumentAssembler assembler = new HotelDocumentAssembler(minPrices, CLOCK);
    private final ReindexService service =
            new ReindexService(lock, hotels, assembler, index, new HotelIndexSync(hotels, assembler, index), changes);

    {
        when(lock.runExclusively(any())).thenAnswer(invocation -> {
            running = true;
            try {
                return Optional.of(invocation.<Supplier<?>>getArgument(0).get());
            } finally {
                running = false;
            }
        });
        when(changes.databaseNow()).thenReturn(DB_NOW);
        when(index.createIndex()).thenReturn(NEW_INDEX);
        when(minPrices.byMonth(any(), any(), anyInt())).thenReturn(Map.of());
        when(changes.hotelIdsChangedSince(any())).thenReturn(List.of());
    }

    /** True while the work runs under the (mocked) lock. */
    private boolean running;

    private static HotelSnapshot active(UUID id) {
        return new HotelSnapshot(id, "Hotel", "Rabat", null, null, null, List.of(), true, List.of());
    }

    /** {@code count} active hotels with ascending ids, as activePage returns them. */
    private static List<HotelSnapshot> hotels(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> active(new UUID(1, i + 1)))
                .toList();
    }

    @Test
    void aReindexAlreadyRunningIsAConflictAndTouchesNothing() {
        doReturn(Optional.empty()).when(lock).runExclusively(any());

        assertThatThrownBy(service::reindex)
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("REINDEX_IN_PROGRESS");
        verifyNoInteractions(index, hotels, changes, minPrices);
    }

    @Test
    void everyStepRunsWhileTheLockIsHeld() {
        when(hotels.activePage(isNull(), anyInt())).thenReturn(List.of());
        UUID edited = UUID.randomUUID();
        when(changes.hotelIdsChangedSince(any())).thenReturn(List.of(edited));
        when(hotels.find(edited)).thenAnswer(invocation -> {
            assertThat(running).isTrue();
            return Optional.empty();
        });
        when(index.createIndex()).thenAnswer(invocation -> {
            assertThat(running).isTrue();
            return NEW_INDEX;
        });

        service.reindex();

        verify(hotels).find(edited);
        assertThat(running).isFalse();
    }

    @Test
    void pagesThroughEveryActiveHotelThenSwapsTheAlias() {
        List<HotelSnapshot> all = hotels(ReindexService.PAGE_SIZE + 2);
        List<HotelSnapshot> first = all.subList(0, ReindexService.PAGE_SIZE);
        List<HotelSnapshot> second = all.subList(ReindexService.PAGE_SIZE, all.size());
        when(hotels.activePage(null, ReindexService.PAGE_SIZE)).thenReturn(first);
        when(hotels.activePage(first.getLast().id(), ReindexService.PAGE_SIZE)).thenReturn(second);

        ReindexResult result = service.reindex();

        assertThat(result).isEqualTo(new ReindexResult(NEW_INDEX, all.size(), 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HotelDocument>> pages = ArgumentCaptor.forClass(List.class);
        var order = inOrder(index);
        order.verify(index).createIndex();
        order.verify(index, org.mockito.Mockito.times(2)).bulkIndex(eq(NEW_INDEX), pages.capture());
        order.verify(index).switchAliasTo(NEW_INDEX);
        assertThat(pages.getAllValues().stream().flatMap(List::stream).map(HotelDocument::id))
                .containsExactlyElementsOf(all.stream().map(HotelSnapshot::id).toList());
        // a short page is the end: no third read
        verify(hotels, never()).activePage(second.getLast().id(), ReindexService.PAGE_SIZE);
        verify(index, never()).deleteIndex(anyString());
    }

    @Test
    void anExactlyFullLastPageEndsOnTheNextEmptyPage() {
        List<HotelSnapshot> first = hotels(ReindexService.PAGE_SIZE);
        when(hotels.activePage(null, ReindexService.PAGE_SIZE)).thenReturn(first);
        when(hotels.activePage(first.getLast().id(), ReindexService.PAGE_SIZE)).thenReturn(List.of());

        assertThat(service.reindex().indexed()).isEqualTo(ReindexService.PAGE_SIZE);
        verify(index).bulkIndex(eq(NEW_INDEX), anyList());
        verify(index).switchAliasTo(NEW_INDEX);
    }

    @Test
    void anEmptyCatalogStillSwapsInAnEmptyIndex() {
        when(hotels.activePage(isNull(), anyInt())).thenReturn(List.of());

        assertThat(service.reindex()).isEqualTo(new ReindexResult(NEW_INDEX, 0, 0));
        verify(index).switchAliasTo(NEW_INDEX);
    }

    @Test
    void aFailureBeforeTheSwapDeletesTheNewIndexAndLeavesTheAlias() {
        when(hotels.activePage(null, ReindexService.PAGE_SIZE)).thenReturn(hotels(1));
        var rejected = new IllegalStateException("bulk rejected");
        doThrow(rejected).when(index).bulkIndex(eq(NEW_INDEX), anyList());

        assertThatThrownBy(service::reindex).isSameAs(rejected);

        verify(index).deleteIndex(NEW_INDEX);
        verify(index, never()).switchAliasTo(anyString());
        verify(changes, never()).hotelIdsChangedSince(any());
    }

    @Test
    void aFailedSwapAlsoDeletesTheNewIndex() {
        when(hotels.activePage(isNull(), anyInt())).thenReturn(List.of());
        var failed = new IllegalStateException("aliases request failed");
        doThrow(failed).when(index).switchAliasTo(NEW_INDEX);

        assertThatThrownBy(service::reindex).isSameAs(failed);
        verify(index).deleteIndex(NEW_INDEX);
    }

    @Test
    void aCleanupFailureIsSuppressedOntoTheOriginalError() {
        var readFailed = new IllegalStateException("catalog read failed");
        when(hotels.activePage(isNull(), anyInt())).thenThrow(readFailed);
        var cleanupFailed = new IllegalStateException("delete failed");
        doThrow(cleanupFailed).when(index).deleteIndex(NEW_INDEX);

        assertThatThrownBy(service::reindex).isSameAs(readFailed).hasSuppressedException(cleanupFailed);
    }

    @Test
    void aFailedCreateHasNothingToCleanUp() {
        var failed = new IllegalStateException("create failed");
        when(index.createIndex()).thenThrow(failed);

        assertThatThrownBy(service::reindex).isSameAs(failed);
        verify(index, never()).deleteIndex(anyString());
    }

    @Test
    void catchUpReAppliesEveryHotelChangedSinceAMinuteBeforeTheDatabaseStartAfterTheSwap() {
        when(hotels.activePage(isNull(), anyInt())).thenReturn(List.of());
        UUID edited = UUID.randomUUID();
        UUID retired = UUID.randomUUID();
        when(changes.hotelIdsChangedSince(DB_NOW.minus(Duration.ofMinutes(1)))).thenReturn(List.of(edited, retired));
        when(hotels.find(edited)).thenReturn(Optional.of(active(edited)));
        when(hotels.find(retired)).thenReturn(Optional.empty());

        ReindexResult result = service.reindex();

        assertThat(result.caughtUp()).isEqualTo(2);
        var order = inOrder(index);
        order.verify(index).switchAliasTo(NEW_INDEX);
        order.verify(index).upsert(any(HotelDocument.class));
        order.verify(index).delete(retired);
        verify(changes).hotelIdsChangedSince(DB_NOW.minus(Duration.ofMinutes(1)));
    }

    @Test
    void aCatchUpFailurePropagatesAndKeepsTheNewIndexLive() {
        when(hotels.activePage(isNull(), anyInt())).thenReturn(List.of());
        UUID edited = UUID.randomUUID();
        when(changes.hotelIdsChangedSince(any())).thenReturn(new ArrayList<>(List.of(edited)));
        when(hotels.find(edited)).thenReturn(Optional.empty());
        var down = new IllegalStateException("elasticsearch down");
        doThrow(down).when(index).delete(edited);

        assertThatThrownBy(service::reindex).isSameAs(down);
        verify(index).switchAliasTo(NEW_INDEX);
        verify(index, never()).deleteIndex(anyString());
    }
}
