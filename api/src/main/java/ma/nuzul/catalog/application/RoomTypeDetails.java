package ma.nuzul.catalog.application;

/** The admin-editable fields of a room type. Id and parent hotel are never taken from the caller here. */
public record RoomTypeDetails(String name, int capacity, String bedConfig) {}
