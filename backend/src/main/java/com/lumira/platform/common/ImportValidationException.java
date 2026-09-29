package com.lumira.platform.common;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, structured import diagnostics; never includes raw uploaded cell contents. */
public class ImportValidationException extends ResponseStatusException {
    public record Issue(int row,String field,String reason) {}
    private final List<Issue> issues;
    public ImportValidationException(List<Issue> issues) {
        super(HttpStatus.BAD_REQUEST,"invalidDictionaryDataset");
        this.issues=List.copyOf(issues.stream().limit(100).toList());
    }
    public List<Issue> issues(){return issues;}
}
