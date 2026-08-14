package com.example.backend.debug;

import com.example.backend.gtfs.GtfsImportDiagnostic;
import com.example.backend.gtfs.GtfsLoadException;
import com.example.backend.service.FeedUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class DebugApiExceptionHandler {

    @ExceptionHandler(DebugBadRequestException.class)
    ProblemDetail badRequest(DebugBadRequestException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid debug request", exception.getMessage(), exception.code());
    }

    @ExceptionHandler(DebugNotFoundException.class)
    ProblemDetail notFound(DebugNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Debug resource not found", exception.getMessage(), "not_found");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(MissingServletRequestParameterException exception) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "Missing request parameter",
            "Required parameter is missing: " + exception.getParameterName(),
            "missing_parameter"
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException exception) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "Invalid request parameter",
            "Parameter " + exception.getName() + " has an invalid value: " + exception.getValue(),
            "invalid_parameter"
        );
    }

    @ExceptionHandler(FeedUnavailableException.class)
    ProblemDetail feedUnavailable(FeedUnavailableException exception) {
        ProblemDetail problem = problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GTFS feed unavailable",
            exception.getMessage(),
            "feed_unavailable"
        );
        problem.setProperty("feedId", exception.feedId());
        problem.setProperty("source", exception.source().toString());
        exception.diagnostic().ifPresent(diagnostic -> addDiagnostic(problem, diagnostic));
        return problem;
    }

    @ExceptionHandler(GtfsLoadException.class)
    ProblemDetail feedLoadFailure(GtfsLoadException exception) {
        ProblemDetail problem = problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GTFS feed unavailable",
            exception.getMessage(),
            "feed_unavailable"
        );
        addDiagnostic(problem, exception.diagnostic());
        return problem;
    }

    private static void addDiagnostic(
        ProblemDetail problem,
        GtfsImportDiagnostic diagnostic
    ) {
        problem.setProperty("diagnosticCode", diagnostic.code().wireValue());
        problem.setProperty("diagnosticSeverity", diagnostic.severity().wireValue());
        problem.setProperty("feedId", diagnostic.feedId());
        problem.setProperty("source", diagnostic.feedSource().toString());
        problem.setProperty("sourceFile", diagnostic.sourceFile());
        problem.setProperty("entityType", diagnostic.entityType());
        problem.setProperty("entityId", diagnostic.entityId());
        problem.setProperty("field", diagnostic.field());
        problem.setProperty("referencedId", diagnostic.referencedId());
    }

    private static ProblemDetail problem(
        HttpStatus status,
        String title,
        String detail,
        String code
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
