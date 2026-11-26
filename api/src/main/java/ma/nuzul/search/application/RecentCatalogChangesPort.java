package ma.nuzul.search.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Out-port: which hotels had catalog events appended recently (story 3.2, the reindex catch-up). */
public interface RecentCatalogChangesPort {

    /**
     * The database's current time. Outbox rows are stamped with it ({@code created_at}), so a window over them must
     * start from it too, not from the application's clock, which may drift from the database's.
     */
    Instant databaseNow();

    /**
     * Distinct hotel ids of the catalog events ({@code HotelChanged}, {@code RoomTypeChanged},
     * {@code HotelPricesChanged}) appended to the outbox at or after {@code since} (database time), published or not.
     * Events whose payload carries no hotel id are skipped.
     */
    List<UUID> hotelIdsChangedSince(Instant since);
}
