package ma.nuzul.reservation.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ma.nuzul.reservation.application.ReservationRepositoryPort;
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
                                     total_mad, hold_expires_at, idempotency_key, guest_name, guest_email)
            VALUES (:id, :guestSub, :hotelId, :roomTypeId, :checkIn, :checkOut, :rooms, :status,
                    :totalMad::numeric, :holdExpiresAt, :idempotencyKey, :guestName, :guestEmail)
            ON CONFLICT ON CONSTRAINT reservation_idem DO NOTHING
            """;

    static final String FIND_BY_KEY = """
            SELECT id, guest_sub, hotel_id, room_type_id, check_in, check_out, rooms, status, total_mad,
                   hold_expires_at, idempotency_key, guest_name, guest_email
            FROM reservation
            WHERE guest_sub = :guestSub AND idempotency_key = :idempotencyKey
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

    /** Nullable column: only a HELD reservation needs one (later stories must not clear it on a held row). */
    private static java.time.Instant holdExpiresAt(ResultSet rs) throws SQLException {
        Timestamp t = rs.getTimestamp("hold_expires_at");
        return t == null ? null : t.toInstant();
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
                rs.getString("guest_email"));
    }
}
