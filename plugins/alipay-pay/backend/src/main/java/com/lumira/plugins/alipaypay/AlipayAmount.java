package com.lumira.plugins.alipaypay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact conversion between Alipay's decimal CNY amounts and payment-core fen. */
final class AlipayAmount {
    private static final long MAX_FEN = 10_000_000_000L;

    private AlipayAmount() {}

    static long toFen(String yuan) {
        if (yuan == null || yuan.isBlank() || yuan.length() > 32)
            throw new IllegalArgumentException("Alipay amount is missing or invalid");
        try {
            long fen =
                    new BigDecimal(yuan)
                            .setScale(2, RoundingMode.UNNECESSARY)
                            .movePointRight(2)
                            .longValueExact();
            if (fen < 0 || fen > MAX_FEN)
                throw new IllegalArgumentException("Alipay amount is out of range");
            return fen;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Alipay amount must be a CNY value with at most two decimals");
        }
    }

    static String toYuan(long fen) {
        if (fen <= 0 || fen > MAX_FEN)
            throw new IllegalArgumentException("payment amount is out of range");
        return BigDecimal.valueOf(fen, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
