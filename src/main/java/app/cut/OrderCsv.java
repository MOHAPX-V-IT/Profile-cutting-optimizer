package app.cut;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OrderCsv {
    private OrderCsv() {}

    static List<Workshop.LineInput> parse(byte[] bytes, Map<String, Long> profiles) {
        if (bytes == null || bytes.length == 0 || bytes.length > 128 * 1024) {
            throw CutInput.invalid("Выберите CSV размером до 128 КБ.");
        }
        var text = decode(bytes);
        var rows = split(text, separator(text));
        if (rows.size() < 2) throw CutInput.invalid("CSV должен содержать заголовок и хотя бы одну позицию.");
        if (rows.size() > 201) throw CutInput.invalid("В CSV допускается до 200 позиций.");
        var headers = rows.getFirst().stream().map(value -> value.strip().toLowerCase(Locale.ROOT)).toList();
        int code = column(headers, "профиль", "код", "код профиля");
        int length = column(headers, "длина", "длина мм", "длина, мм");
        int quantity = column(headers, "количество", "кол-во", "шт", "шт.");
        if (code < 0 || length < 0 || quantity < 0) {
            throw CutInput.invalid("CSV должен содержать колонки «Профиль», «Длина», «Количество».");
        }

        var result = new ArrayList<Workshop.LineInput>();
        for (int index = 1; index < rows.size(); index++) {
            var row = rows.get(index);
            int line = index + 1;
            if (row.size() != headers.size()) throw rowError(line, "число полей не совпадает с заголовком.");
            String profileCode = row.get(code).strip().toUpperCase(Locale.ROOT);
            Long profileId = profiles.get(profileCode);
            if (profileId == null) throw rowError(line, "профиль «" + profileCode + "» отсутствует в справочнике.");
            String units = row.get(length).strip();
            int count;
            try {
                count = Integer.parseInt(row.get(quantity).strip());
                CutInput.length(units, "Длину", false);
                CutInput.quantity(count, CutInput.MAX_PIECES, "Количество");
            } catch (NumberFormatException | CutInput.Problem failure) {
                throw rowError(line, "проверьте длину в миллиметрах и целое количество.");
            }
            result.add(new Workshop.LineInput(profileId, units, count));
        }
        return result;
    }

    private static String decode(byte[] content) {
        if (content.length >= 2 && ((content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xfe ||
                (content[0] & 0xff) == 0xfe && (content[1] & 0xff) == 0xff)) {
            return new String(content, StandardCharsets.UTF_16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString().replaceFirst("^\\uFEFF", "");
        } catch (CharacterCodingException olderExcel) {
            return new String(content, Charset.forName("windows-1251"));
        }
    }

    private static char separator(String text) {
        boolean quoted = false;
        int comma = 0, semicolon = 0, tab = 0;
        for (char value : text.toCharArray()) {
            if (value == '"') quoted = !quoted;
            if (quoted) continue;
            if (value == '\n' || value == '\r') {
                if (comma + semicolon + tab > 0) break;
            } else if (value == ',') comma++;
            else if (value == ';') semicolon++;
            else if (value == '\t') tab++;
        }
        if (tab >= 2 && tab >= semicolon && tab >= comma) return '\t';
        return semicolon > 0 ? ';' : ',';
    }

    private static List<List<String>> split(String text, char separator) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quoted) {
                if (value == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"'); index++;
                } else if (value == '"') {
                    quoted = false; closed = true;
                } else field.append(value);
            } else if (value == separator || value == '\r' || value == '\n') {
                row.add(field.toString());
                field.setLength(0); closed = false;
                if (value != separator) {
                    if (value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                    addRow(rows, row);
                    row = new ArrayList<>();
                }
            } else if (value == '"' && field.isEmpty() && !closed) {
                quoted = true;
            } else if (closed || value == '"') {
                if (!closed || !Character.isWhitespace(value)) throw CutInput.invalid("Проверьте кавычки полей в CSV.");
            } else field.append(value);
        }
        if (quoted) throw CutInput.invalid("В CSV есть незакрытая кавычка.");
        row.add(field.toString());
        addRow(rows, row);
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(List.copyOf(row));
        if (rows.size() > 201) throw CutInput.invalid("В CSV допускается до 200 позиций.");
    }

    private static int column(List<String> headers, String... aliases) {
        for (String name : aliases) if (headers.contains(name)) return headers.indexOf(name);
        return -1;
    }

    private static CutInput.Problem rowError(int row, String problem) {
        return CutInput.invalid("Строка CSV " + row + ": " + problem);
    }
}
