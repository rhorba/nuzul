package ma.nuzul.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.nuzul.catalog.HotelSnapshot;
import ma.nuzul.inventory.MinPrices;
import org.junit.jupiter.api.Test;

/** Story 3.2: one hotel's search document, built from its catalog snapshot and its cheapest rate per month. */
class HotelDocumentAssemblerTest {

    /** 23:30 UTC on Nov 30 is already Dec 1 in Casablanca (UTC+1): "today" must come from the clock's zone. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-30T23:30:00Z"), ZoneId.of("Africa/Casablanca"));

    private final MinPrices minPrices = mock(MinPrices.class);
    private final HotelDocumentAssembler assembler = new HotelDocumentAssembler(minPrices, CLOCK);

    static HotelSnapshot snapshot(UUID id, boolean active, List<UUID> roomTypeIds) {
        return new HotelSnapshot(
                id, "Riad Atlas", "Marrakech", 31.6295, -7.9811, 4, List.of("wifi", "pool"), active, roomTypeIds);
    }

    @Test
    void buildsTheDocumentWithTwelveMonthsOfPricesFromTodayInTheClockZone() {
        UUID hotelId = UUID.randomUUID();
        List<UUID> roomTypes = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(minPrices.byMonth(roomTypes, LocalDate.of(2026, 12, 1), 12))
                .thenReturn(Map.of(
                        YearMonth.of(2026, 12), new BigDecimal("850.50"),
                        YearMonth.of(2027, 1), new BigDecimal("700.00")));

        HotelDocument document = assembler.assemble(snapshot(hotelId, true, roomTypes));

        assertThat(document)
                .isEqualTo(new HotelDocument(
                        hotelId,
                        "Riad Atlas",
                        "Marrakech",
                        31.6295,
                        -7.9811,
                        4,
                        List.of("wifi", "pool"),
                        Map.of("2026-12", new BigDecimal("850.50"), "2027-01", new BigDecimal("700.00"))));
        verify(minPrices).byMonth(roomTypes, LocalDate.of(2026, 12, 1), 12);
    }

    @Test
    void aHotelWithoutCoordinatesOrPricesStillBuilds() {
        UUID hotelId = UUID.randomUUID();
        var bare = new HotelSnapshot(hotelId, "Dar", "Fes", null, null, null, List.of(), true, List.of());
        when(minPrices.byMonth(List.of(), LocalDate.of(2026, 12, 1), 12)).thenReturn(Map.of());

        HotelDocument document = assembler.assemble(bare);

        assertThat(document.lat()).isNull();
        assertThat(document.stars()).isNull();
        assertThat(document.minPriceByMonth()).isEmpty();
    }

    @Test
    void refusesAnInactiveHotel() {
        assertThatThrownBy(() -> assembler.assemble(snapshot(UUID.randomUUID(), false, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(minPrices);
    }
}
