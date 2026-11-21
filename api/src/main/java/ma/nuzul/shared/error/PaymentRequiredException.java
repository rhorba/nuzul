package ma.nuzul.shared.error;

import java.util.Map;

/** The payment was refused (402); the caller may try again with another payment method. */
public class PaymentRequiredException extends CodedException {

    public PaymentRequiredException(String code, String message) {
        super(code, message, Map.of());
    }
}
