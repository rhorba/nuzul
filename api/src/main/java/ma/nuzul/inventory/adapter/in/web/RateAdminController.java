package ma.nuzul.inventory.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ma.nuzul.inventory.application.ManageRatesUseCase;
import ma.nuzul.inventory.domain.NightBatch;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk per-night rates for one of the caller's room types; the owner is always {@code jwt.sub}. All or nothing:
 * 204 when every night is written, otherwise nothing is.
 */
@RestController
@RequestMapping("/api/v1/admin/room-types/{roomTypeId}/rates")
@PreAuthorize("hasRole('HOTEL_ADMIN')")
class RateAdminController {

    private final ManageRatesUseCase rates;

    RateAdminController(ManageRatesUseCase rates) {
        this.rates = rates;
    }

    @PutMapping
    ResponseEntity<Void> setRates(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID roomTypeId,
            @RequestBody @Size(min = 1, max = NightBatch.MAX_SIZE) List<@NotNull @Valid RateNightRequest> nights) {
        rates.setRates(
                jwt.getSubject(),
                roomTypeId,
                nights.stream().map(RateNightRequest::toChange).toList());
        return ResponseEntity.noContent().build();
    }
}
