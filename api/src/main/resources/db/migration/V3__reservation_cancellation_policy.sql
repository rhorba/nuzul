-- Story 2.3: the cancellation policy is frozen on the reservation at hold time, like total_mad, so a later rate edit
-- by the hotel admin never changes a guest's terms.
--   refundable        every night of the stay was refundable when it was held
--   free_cancel_until cancelling before this instant refunds in full; NULL = never free
-- The deadline is the check-in day at 00:00 in the hotel time zone (Africa/Casablanca, app.time-zone) minus the
-- strictest free_cancel_hours of the stay's nights. The backfill hard-codes the default zone: a deployment that
-- overrides APP_TIME_ZONE before its first reservation is unaffected (there is nothing to backfill).
ALTER TABLE reservation
  ADD COLUMN refundable BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN free_cancel_until TIMESTAMPTZ;

-- Backfill holds made before V3 from their (current) rates. Rows whose rates are missing stay non-refundable.
UPDATE reservation r
SET refundable = p.all_refundable,
    free_cancel_until = CASE WHEN p.all_refundable
      THEN (r.check_in::timestamp AT TIME ZONE 'Africa/Casablanca') - make_interval(hours => p.max_hours) END
FROM (
  SELECT res.id, bool_and(rt.refundable) AS all_refundable, max(rt.free_cancel_hours) AS max_hours,
         count(rt.night) AS priced, (res.check_out - res.check_in) AS nights
  FROM reservation res
  JOIN rate rt ON rt.room_type_id = res.room_type_id AND rt.night >= res.check_in AND rt.night < res.check_out
  GROUP BY res.id
) p
WHERE p.id = r.id AND p.priced = p.nights;
