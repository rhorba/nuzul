package ma.nuzul.reservation.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /reservations/{id}/confirm body: an opaque token from the payment provider's client SDK. */
record ConfirmReservationRequest(@NotBlank @Size(max = 255) String paymentToken) {}
