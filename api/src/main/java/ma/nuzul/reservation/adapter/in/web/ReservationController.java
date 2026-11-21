package ma.nuzul.reservation.adapter.in.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import ma.nuzul.reservation.application.ConfirmReservationUseCase;
import ma.nuzul.reservation.application.HeldReservation;
import ma.nuzul.reservation.application.HoldReservationUseCase;
import ma.nuzul.reservation.domain.Guest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Guest reservations. The guest is always the token's subject; name and email come from its standard OIDC claims
 * ({@code name}, falling back to {@code preferred_username}; {@code email}), never from the request body.
 */
@RestController
@RequestMapping("/api/v1/reservations")
@PreAuthorize("hasRole('GUEST')")
class ReservationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final HoldReservationUseCase holds;
    private final ConfirmReservationUseCase confirmations;

    ReservationController(HoldReservationUseCase holds, ConfirmReservationUseCase confirmations) {
        this.holds = holds;
        this.confirmations = confirmations;
    }

    /** 201 for the first call and for every replay of the same key: the body is identical each time. */
    @PostMapping
    ResponseEntity<HeldReservation> hold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody HoldReservationRequest request) {
        HeldReservation held = holds.hold(guest(jwt), idempotencyKey, request.toHoldRequest());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(held.id())
                .toUri();
        return ResponseEntity.created(location).body(held);
    }

    /** 200 for the confirm and for every retry of it; another guest's reservation is 404. */
    @PostMapping("/{id}/confirm")
    ConfirmReservationUseCase.Confirmed confirm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmReservationRequest request) {
        return confirmations.confirm(jwt.getSubject(), id, request.paymentToken());
    }

    static Guest guest(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) {
            name = jwt.getClaimAsString("preferred_username");
        }
        return new Guest(jwt.getSubject(), name, jwt.getClaimAsString("email"));
    }
}
