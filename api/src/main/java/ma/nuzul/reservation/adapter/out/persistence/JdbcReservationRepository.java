package ma.nuzul.reservation.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.application.ReservationRepositoryPort;
import ma.nuzul.reservation.domain.CancellationPolicy;
import ma.nuzul.reservation.domain.Reservation;
import ma.nuzul.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC on reservation, like inventory: every statement is explicit SQL. Money travels as NUMERIC text. */
@Repository
class JdbcReservationRepository implements ReservationRepositoryPort {

    /*
     * ON CONFLICT on the reservation_idem constraint only (never a blanket DO NOTHING): an insert racing a concurrent
     * one with the same (guest_sub, idempotency_key) waits for that transaction, then inserts nothing if it committed
     * (0 rows) or goes ahead if it rolled back. No 23505 is raised, so the transaction stays usable.
     */
    static final String INSERT = """
            INSERT INTO reservation (id, guest_sub, hotel_id, room_type_id, check_in, check_out, rooms, status,
                                     total_mad, hold_expires_at, idempotency_key, guest_name, guest_email,
                                     refundable, free_cancel_until)
            VALUES (:id, :guestSub, :hotelId, :roomTypeId, :checkIn, :checkOut, :rooms, :status,
                    :totalMad::numeric, :holdExpiresAt, :idempotencyKey, :guestName, :guestEmail,
                    :refundable, :freeCancelUntil)
            ON CONFLICT ON CONSTRAINT reservation_idem DO NOTHING
            """;

    static final String SELECT_COLUMNS = """
            SELECT id, guest_sub, hotel_id, room_type_id, check_in, check_out, rooms, status, total_mad,
                   hold_expires_at, idempotency_key, guest_name, guest_email, refundable, free_cancel_until
            FROM reservation""";

    static final String FIND_BY_KEY =
            SELECT_COLUMNS + " WHERE guest_sub = :guestSub AND idempotency_key = :idempotencyKey";

    /*
     * Two-int advisory lock: a fixed namespace for "a guest's holds" plus the guest. hashtext() is fine for a lock that
     * lives one transaction (it never has to match a value stored on disk); a collision between two guests only makes
     * them wait for each other briefly, never lets one skip the cap. The namespace keeps these locks apart from any
     * other advisory lock in the database.
     */
    static final String LOCK_GUEST_HOLDS =
            "SELECT pg_advisory_xact_lock(hashtext('nuzul.reservation.hold'), hashtext(:guestSub))";

    /* Uses reservation_guest (guest_sub, created_at DESC): a guest has few rows, so the filter after it is cheap. */
    static final String COUNT_ACTIVE_HOLDS = """
            SELECT count(*) FROM reservation
            WHERE guest_sub = :guestSub AND status = 'HELD' AND hold_expires_at > :now
            """;

    private final JdbcClient jdbc;

    JdbcReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfKeyAbsent(Reservation r) {
        int inserted = jdbc.sql(INSERT)
                .param("id", r.id())
                .param("guestSub", r.guestSub())
                .param("hotelId", r.hotelId())
                .param("roomTypeId", r.roomTypeId())
                .param("checkIn", r.checkIn())
                .param("checkOut", r.checkOut())
                .param("rooms", r.rooms())
                .param("status", r.status().name())
                .param("totalMad", r.totalMad().toPlainString())
                .param("holdExpiresAt", Timestamp.from(r.holdExpiresAt()))
                .param("idempotencyKey", r.idempotencyKey())
                .param("guestName", r.guestName())
                .param("guestEmail", r.guestEmail())
                .param("refundable", r.cancellationPolicy().refundable())
                .param("freeCancelUntil", timestamp(r.cancellationPolicy().freeUntil()))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<Reservation> findByIdempotencyKey(String guestSub, UUID idempotencyKey) {
        return jdbc.sql(FIND_BY_KEY)
                .param("guestSub", guestSub)
                .param("idempotencyKey", idempotencyKey)
                .query(JdbcReservationRepository::map)
                .optional();
    }

    @Override
    public void lockGuestHolds(String guestSub) {
        jdbc.sql(LOCK_GUEST_HOLDS)
                .param("guestSub", guestSub)
                .query((rs, i) -> i)
                .single();
    }

    @Override
    public int countActiveHolds(String guestSub, Instant now) {
        return jdbc.sql(COUNT_ACTIVE_HOLDS)
                .param("guestSub", guestSub)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single()
                .intValue();
    }

    @Override
    public Optional<Reservation> findForGuest(UUID id, String guestSub) {
        return jdbc.sql(SELECT_COLUMNS + " WHERE id = :id AND guest_sub = :guestSub")
                .param("id", id)
                .param("guestSub", guestSub)
                .query(JdbcReservationRepository::map)
                .optional();
    }

    @Override
    public Optional<ReservationStatus> findStatus(UUID id) {
        return jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> ReservationStatus.valueOf(rs.getString("status")))
                .optional();
    }

    @Override
    public boolean confirmIfHeld(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CONFIRMED', version = version + 1
                        WHERE id = :id AND status = 'HELD' AND hold_expires_at > :now
                        """).param("id", id).param("now", Timestamp.from(now)).update() == 1;
    }

    @Override
    public boolean cancelIf(UUID id, ReservationStatus from) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'CANCELLED', version = version + 1
                        WHERE id = :id AND status = :from
                        """).param("id", id).param("from", from.name()).update() == 1;
    }

    @Override
    public List<UUID> expiredHoldIds(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM reservation
                        WHERE status = 'HELD' AND hold_expires_at <= :now
                        ORDER BY hold_expires_at
                        LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    @Override
    public Optional<Reservation> lockExpiredHold(UUID id, Instant now) {
        return jdbc.sql(SELECT_COLUMNS
                        + " WHERE id = :id AND status = 'HELD' AND hold_expires_at <= :now FOR UPDATE SKIP LOCKED")
                .param("id", id)
                .param("now", Timestamp.from(now))
                .query(JdbcReservationRepository::map)
                .optional();
    }

    @Override
    public boolean expireIfHeld(UUID id) {
        return jdbc.sql("""
                        UPDATE reservation SET status = 'EXPIRED', version = version + 1
                        WHERE id = :id AND status = 'HELD'
                        """).param("id", id).update() == 1;
    }

    /** Nullable column: only a HELD reservation needs one (later stories must not clear it on a held row). */
    private static Instant holdExpiresAt(ResultSet rs) throws SQLException {
        return instant(rs, "hold_expires_at");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getString("guest_sub"),
                rs.getObject("hotel_id", UUID.class),
                rs.getObject("room_type_id", UUID.class),
                rs.getObject("check_in", LocalDate.class),
                rs.getObject("check_out", LocalDate.class),
                rs.getInt("rooms"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("total_mad"),
                holdExpiresAt(rs),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("guest_name"),
                rs.getString("guest_email"),
                new CancellationPolicy(rs.getBoolean("refundable"), instant(rs, "free_cancel_until")));
    }
}
