package ma.nuzul.inventory;

import java.math.BigDecimal;

/**
 * The price and cancellation terms of a stay, read from its rate rows at hold time.
 *
 * @param totalMad the sum of each night's price times the rooms, exact NUMERIC
 * @param refundable true only when every night of the stay is refundable
 * @param freeCancelHours the strictest (largest) free_cancel_hours among the stay's nights
 */
public record StayQuote(BigDecimal totalMad, boolean refundable, int freeCancelHours) {}
