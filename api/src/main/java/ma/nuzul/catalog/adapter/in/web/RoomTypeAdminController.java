package ma.nuzul.catalog.adapter.in.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import ma.nuzul.catalog.application.ManageRoomTypesUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Room-type admin CRUD under one of the caller's hotels; the owner is always {@code jwt.sub}, never the body.
 * There is no DELETE.
 */
@RestController
@RequestMapping("/api/v1/admin/hotels/{hotelId}/room-types")
@PreAuthorize("hasRole('HOTEL_ADMIN')")
class RoomTypeAdminController {

    private final ManageRoomTypesUseCase roomTypes;

    RoomTypeAdminController(ManageRoomTypesUseCase roomTypes) {
        this.roomTypes = roomTypes;
    }

    @PostMapping
    ResponseEntity<RoomTypeResponse> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID hotelId, @Valid @RequestBody RoomTypeRequest request) {
        var created = RoomTypeResponse.from(roomTypes.create(jwt.getSubject(), hotelId, request.toDetails()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * A plain JSON array, deliberately not paginated: a hotel has a handful of room types, so the list is small and
     * bounded by the business itself. Ordered by name, then id for a stable order.
     */
    @GetMapping
    List<RoomTypeResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID hotelId) {
        return roomTypes.list(jwt.getSubject(), hotelId).stream()
                .map(RoomTypeResponse::from)
                .toList();
    }

    @PutMapping("/{id}")
    RoomTypeResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID hotelId,
            @PathVariable UUID id,
            @Valid @RequestBody RoomTypeRequest request) {
        return RoomTypeResponse.from(roomTypes.update(jwt.getSubject(), hotelId, id, request.toDetails()));
    }
}
