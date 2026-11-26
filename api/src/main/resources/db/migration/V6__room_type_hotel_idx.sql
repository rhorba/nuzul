-- Story 3.2: Postgres does not index foreign-key columns. The search indexer reads a hotel's room types on every
-- catalog event (HotelSnapshots.find) and per reindex page (activePage), both by hotel_id.
CREATE INDEX room_type_hotel ON room_type (hotel_id);
