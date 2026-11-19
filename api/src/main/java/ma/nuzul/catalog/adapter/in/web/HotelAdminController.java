package ma.nuzul.catalog.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import ma.nuzul.catalog.application.ManageHotelsUseCase;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Hotel admin CRUD, scoped to the caller: the owner is always {@code jwt.sub}, never the request body. There is
 * no DELETE; a hotel is retired with PUT {@code status=INACTIVE}.
 */
@RestController
@RequestMapping("/api/v1/admin/hotels")
@PreAuthorize("hasRole('HOTEL_ADMIN')")
class HotelAdminController {

    static final int MAX_PAGE_SIZE = 100;

    private final ManageHotelsUseCase hotels;

    HotelAdminController(ManageHotelsUseCase hotels) {
        this.hotels = hotels;
    }

    @PostMapping
    ResponseEntity<HotelResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HotelRequest request) {
        var created = HotelResponse.from(hotels.create(jwt.getSubject(), request.toDetails()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    HotelResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return HotelResponse.from(hotels.get(jwt.getSubject(), id));
    }

    @PutMapping("/{id}")
    HotelResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateHotelRequest request) {
        return HotelResponse.from(hotels.update(jwt.getSubject(), id, request.toDetails(), request.status()));
    }

    /** Sizes above {@value #MAX_PAGE_SIZE} are rejected with 400 VALIDATION_FAILED rather than silently clamped. */
    @GetMapping
    PageResponse<HotelResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return PageResponse.from(hotels.list(jwt.getSubject(), page, size), HotelResponse::from);
    }
}
