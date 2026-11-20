package ma.nuzul.catalog.application;

import org.springframework.modulith.NamedInterface;

/** The admin-editable fields of a room type. Id and parent hotel are never taken from the caller here. */
@NamedInterface("admin")
public record RoomTypeDetails(String name, int capacity, String bedConfig) {}
