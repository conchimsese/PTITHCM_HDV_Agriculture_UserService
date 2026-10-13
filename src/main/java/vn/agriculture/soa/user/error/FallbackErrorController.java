package vn.agriculture.soa.user.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Keeps servlet error dispatches on the same sanitized JSON contract as MVC/security errors. */
@RestController
public class FallbackErrorController implements ErrorController {
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object rawStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = rawStatus instanceof Integer value ? HttpStatus.resolve(value) : null;
        if (status == null || !status.isError()) { status = HttpStatus.INTERNAL_SERVER_ERROR; }
        String code = switch (status) {
            case BAD_REQUEST -> "VALIDATION_ERROR";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case SERVICE_UNAVAILABLE -> "DEPENDENCY_UNAVAILABLE";
            default -> "INTERNAL_SERVER_ERROR";
        };
        Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new ApiError(Instant.now(), status.value(), code, "Request could not be completed", path));
    }
}
