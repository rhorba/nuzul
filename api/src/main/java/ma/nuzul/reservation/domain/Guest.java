package ma.nuzul.reservation.domain;

import ma.nuzul.shared.error.DomainValidationException;

/** The authenticated guest: {@code sub} is the JWT subject; name and email come from the token's claims. */
public record Guest(String sub, String name, String email) {

    public Guest {
        requireText(sub, "sub");
        requireText(name, "name");
        requireText(email, "email");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("the access token has no guest " + field);
        }
    }
}
