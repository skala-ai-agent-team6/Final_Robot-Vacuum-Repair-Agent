package com.skala.backend.docs;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Serves the reviewed contract; domain APIs keep their existing authentication. */
@Controller
public class SwaggerController {
    @GetMapping({"/swagger-ui.html", "/swagger-ui"})
    public String ui() { return "redirect:/swagger-ui/index.html"; }

    @GetMapping(value="/v3/api-docs", produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> contract() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .body(new ClassPathResource("api-docs/openapi.json"));
    }
}
