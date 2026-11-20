package ma.nuzul.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ma.nuzul.catalog.application.HotelDetails;
import ma.nuzul.catalog.application.ManageHotelsUseCase;
import ma.nuzul.catalog.application.ManageRoomTypesUseCase;
import ma.nuzul.catalog.application.PageResult;
import ma.nuzul.catalog.application.RoomTypeDetails;
import ma.nuzul.catalog.domain.Hotel;
import ma.nuzul.catalog.domain.HotelStatus;
import ma.nuzul.catalog.domain.RoomType;
import ma.nuzul.inventory.application.ManageInventoryUseCase;
import ma.nuzul.inventory.application.ManageRatesUseCase;
import ma.nuzul.shared.error.DomainValidationException;
import org.junit.jupiter.api.Test;

/** Call order and failure handling of the seeder, against mocked use cases; no Spring. */
class DemoSeederTest {

    private static final String OWNER = "seed-owner";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.ofHours(1));

    private final ManageHotelsUseCase hotels = mock(ManageHotelsUseCase.class);
    private final ManageRoomTypesUseCase roomTypes = mock(ManageRoomTypesUseCase.class);
    private final ManageInventoryUseCase inventory = mock(ManageInventoryUseCase.class);
    private final ManageRatesUseCase rates = mock(ManageRatesUseCase.class);
    private final DemoSeeder seeder = new DemoSeeder(hotels, roomTypes, inventory, rates, CLOCK, OWNER);

    @Test
    void writesEachRoomTypesRatesBeforeItsTotals() {
        when(hotels.list(eq(OWNER), anyInt(), anyInt())).thenReturn(new PageResult<>(List.of(), 0, 100, 0));
        when(hotels.create(eq(OWNER), any(HotelDetails.class))).thenAnswer(inv -> {
            HotelDetails d = inv.getArgument(1);
            return new Hotel(
                    UUID.randomUUID(),
                    OWNER,
                    d.name(),
                    d.city(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    HotelStatus.ACTIVE,
                    null,
                    null);
        });
        when(roomTypes.create(eq(OWNER), any(UUID.class), any(RoomTypeDetails.class)))
                .thenAnswer(inv -> {
                    RoomTypeDetails d = inv.getArgument(2);
                    return new RoomType(UUID.randomUUID(), inv.getArgument(1), d.name(), d.capacity(), d.bedConfig());
                });
        when(inventory.stockedNights(eq(OWNER), any(UUID.class))).thenReturn(Set.of());

        List<String> calls = new ArrayList<>();
        doAnswer(inv -> calls.add("rates " + inv.getArgument(1)))
                .when(rates)
                .setRates(eq(OWNER), any(UUID.class), anyList());
        doAnswer(inv -> calls.add("totals " + inv.getArgument(1)))
                .when(inventory)
                .setTotals(eq(OWNER), any(UUID.class), anyList());

        seeder.seed();

        assertThat(calls).hasSize(2 * 180);
        for (int i = 0; i < calls.size(); i += 2) {
            String roomType = calls.get(i).substring("rates ".length());
            assertThat(calls.subList(i, i + 2)).containsExactly("rates " + roomType, "totals " + roomType);
        }
    }

    @Test
    void aFailedRunIsLoggedWithoutStoppingStartup() {
        when(hotels.list(eq(OWNER), anyInt(), anyInt()))
                .thenThrow(new DomainValidationException("night outside the booking window"));

        assertThatNoException().isThrownBy(() -> seeder.run(null));
    }
}
