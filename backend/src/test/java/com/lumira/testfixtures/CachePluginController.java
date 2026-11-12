package com.lumira.testfixtures;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** A top-level type that can be independently loaded without a host nestmate. */
@RestController
public class CachePluginController {
    @GetMapping("/plugin/typed")
    public CachePluginController typed() {
        return this;
    }

    public String getValue() {
        return "plugin";
    }

    @GetMapping("/plugin/cache")
    public Map<String, String> value(
            @RequestParam String value, @RequestParam(defaultValue = "0") int delay) {
        if (value.equals("fail")) throw new IllegalArgumentException();
        return Map.of("value", value);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> failure() {
        return Map.of("code", "invalidRequest");
    }
}
