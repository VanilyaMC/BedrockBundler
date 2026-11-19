package dev.bedrockbundler.common.model;

import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Number helpers that match the Python tool's {@code temiz()}: rounded to 5 decimals, integers written without a
 * fraction, never in exponent notation.
 */
final class JsonNum extends Number {

    private final double value;

    private JsonNum(double value) {
        this.value = value;
    }

    /** Rounds like Python's {@code round(x, 5)} (half even on the exact binary value). */
    static double round5(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return Double.parseDouble(new BigDecimal(v).setScale(5, RoundingMode.HALF_EVEN).toString());
    }

    /** {@code temiz(v)}: a whole number as a long, else the rounded value. */
    static JsonPrimitive of(double v) {
        double r = round5(v);
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            return new JsonPrimitive((long) r);
        }
        return new JsonPrimitive(new JsonNum(r));
    }

    /** A value written as is (no rounding), still without exponent notation. */
    static JsonPrimitive raw(double v) {
        return new JsonPrimitive(new JsonNum(v));
    }

    /** {@code str(temiz(v))}. */
    static String text(double v) {
        double r = round5(v);
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            return Long.toString((long) r);
        }
        return plain(r);
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).toPlainString();
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return (long) value;
    }

    @Override
    public float floatValue() {
        return (float) value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public String toString() {
        return plain(value);
    }
}
