package ma.nuzul.payment.adapter.out.fake;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import ma.nuzul.payment.ChargeResult;
import org.junit.jupiter.api.Test;

class FakePaymentAdapterTest {

    private final FakePaymentAdapter provider = new FakePaymentAdapter();
    private final BigDecimal amount = new BigDecimal("700.00");

    @Test
    void approvesAnyCardExceptTheDeclinedTestCard() {
        assertThat(provider.charge("k1", amount, "tok_visa_4242")).isEqualTo(new ChargeResult.Approved("fake_ch_k1"));
        assertThat(provider.charge("k1", amount, "tok_visa_0002")).isInstanceOf(ChargeResult.Declined.class);
        assertThat(provider.charge("k1", amount, " ")).isInstanceOf(ChargeResult.Declined.class);
        assertThat(provider.charge("k1", amount, null)).isInstanceOf(ChargeResult.Declined.class);
    }

    @Test
    void theSameIdempotencyKeyGivesTheSameCharge() {
        assertThat(provider.charge("k2", amount, "tok_a")).isEqualTo(provider.charge("k2", amount, "tok_b"));
    }

    @Test
    void refundsReferenceTheCharge() {
        assertThat(provider.refund("fake_ch_k3", amount)).isEqualTo("fake_re_fake_ch_k3");
    }
}
