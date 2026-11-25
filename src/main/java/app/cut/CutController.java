package app.cut;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
class CutController {
    private final Workshop workshop;

    CutController(Workshop workshop) { this.workshop = workshop; }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/api/workspace")
    Workshop.Snapshot snapshot() { return workshop.snapshot(); }

    @PostMapping("/api/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    Workshop.Profile profile(@RequestBody Workshop.ProfileInput input) { return workshop.addProfile(input); }

    @PostMapping("/api/stock")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Integer> stock(@RequestBody Workshop.StockInput input) { return Map.of("quantity", workshop.addStock(input)); }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> order(@RequestBody Workshop.OrderInput input) { return Map.of("id", workshop.addOrder(input)); }

    @PostMapping(value="/api/orders/import", consumes="multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> importOrder(@RequestParam String name, @RequestParam(required=false) String customer,
                                  @RequestPart MultipartFile file) throws IOException {
        return Map.of("id", workshop.importOrder(name, customer, file.getBytes()));
    }

    @GetMapping("/api/plans/{id}")
    Workshop.PlanDetails plan(@PathVariable long id) { return workshop.plan(id); }

    @PostMapping("/api/plans")
    @ResponseStatus(HttpStatus.CREATED)
    Workshop.PlanDetails plan(@RequestBody Workshop.PlanInput input) { return workshop.calculate(input); }

    @PostMapping("/api/plans/{id}/reserve")
    Workshop.PlanDetails reserve(@PathVariable long id) { return workshop.reserve(id); }

    @PostMapping("/api/plans/{id}/cancel")
    Workshop.PlanDetails cancel(@PathVariable long id) { return workshop.cancel(id); }

    @PostMapping("/api/plans/{id}/confirm")
    Workshop.PlanDetails confirm(@PathVariable long id, @RequestBody Workshop.FactInput input) { return workshop.confirm(id, input); }

    @PostMapping("/api/demo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void demo() { workshop.demo(); }
}

@RestControllerAdvice
class CutErrors {
    @ExceptionHandler(CutInput.Problem.class)
    ResponseEntity<Map<String, String>> business(CutInput.Problem problem) {
        return ResponseEntity.status(problem.status()).body(Map.of("message", problem.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, MissingServletRequestPartException.class, IOException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> malformed() { return Map.of("message", "Проверьте формат и обязательные поля запроса."); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    Map<String, String> tooLarge() { return Map.of("message", "CSV должен быть не больше 128 КБ."); }
}
