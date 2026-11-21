package ma.nuzul.payment.adapter.out.fake;

import java.math.BigDecimal;
import ma.nuzul.payment.ChargeResult;
import ma.nuzul.payment.application.PaymentPort;
import org.springframework.stereotype.Component;

/**
 * The v1 payment provider: no money moves (system design, Payment: fake adapter behind a port). A token ending in
 * {@value #DECLINED_SUFFIX} is declined, like a test card; any other token is approved. References are derived from
 * the idempotency key, so a repeated charge returns the same answer, as a real provider's idempotency would.
 */
@Component
class FakePaymentAdapter implements PaymentPort {

    static final String DECLINED_SUFFIX = "0002";

    @Override
    public ChargeResult charge(String idempotencyKey, BigDecimal amountMad, String paymentToken) {
        if (paymentToken == null || paymentToken.isBlank()) {
            return new ChargeResult.Declined("missing payment token");
        }
        if (paymentToken.endsWith(DECLINED_SUFFIX)) {
            return new ChargeResult.Declined("card declined");
        }
        return new ChargeResult.Approved("fake_ch_" + idempotencyKey);
    }

    @Override
    public String refund(String idempotencyKey, String chargeRef, BigDecimal amountMad) {
        return "fake_re_" + idempotencyKey;
    }
}
