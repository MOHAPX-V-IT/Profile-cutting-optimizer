package app.cut;

import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.http.HttpStatus;

final class CutInput {
    static final long MAX_LENGTH = 1_000_000L;
    static final int MAX_PIECES = 2000;
    static final int MAX_SELECTED_ORDERS = 30;
    static final int MAX_STOCK = 10_000;

    private CutInput() {}

    static long length(String raw, String label, boolean zeroAllowed) {
        if (raw == null || !raw.strip().matches("\\d{1,6}(?:[.,]\\d)?")) {
            throw invalid(label + " укажите в миллиметрах, до одного знака после запятой.");
        }
        long units = new BigDecimal(raw.strip().replace(',', '.')).movePointRight(1).longValueExact();
        if (units > MAX_LENGTH || units < (zeroAllowed ? 0 : 1)) {
            throw invalid(label + " вне диапазона до 100 000 мм.");
        }
        return units;
    }

    static String mm(long units) {
        return units / 10 + (units % 10 == 0 ? "" : "," + units % 10);
    }

    static String required(String raw, int maximum, String label) {
        var value = optional(raw, maximum, label);
        if (value.isEmpty()) throw invalid("Заполните поле «" + label + "».");
        return value;
    }

    static String optional(String raw, int maximum, String label) {
        var value = raw == null ? "" : raw.strip();
        if (value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(label + " содержит недопустимые символы или превышает " + maximum + " знаков.");
        }
        return value;
    }

    static String code(String raw) {
        return required(raw, 40, "Код профиля").toUpperCase(Locale.ROOT);
    }

    static int quantity(Integer value, int max, String label) {
        if (value == null || value < 1 || value > max) throw invalid(label + " должно быть от 1 до " + max + ".");
        return value;
    }

    static Problem invalid(String message) { return new Problem(HttpStatus.BAD_REQUEST, message); }
    static Problem conflict(String message) { return new Problem(HttpStatus.CONFLICT, message); }
    static Problem missing(String message) { return new Problem(HttpStatus.NOT_FOUND, message); }

    static final class Problem extends RuntimeException {
        private final HttpStatus status;
        Problem(HttpStatus status, String message) { super(message); this.status = status; }
        HttpStatus status() { return status; }
    }
}
