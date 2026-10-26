package com.threesixty.futureself.generation;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class GenerationController {
    private final GenerationService service;
    public GenerationController(GenerationService service) { this.service = service; }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok", "service", "360-future-self"); }

    @PostMapping(value = "/generations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> create(
            @RequestPart("image") MultipartFile image,
            @RequestParam("name") String name,
            @RequestParam("age") int age,
            @RequestParam("occupation") String occupation,
            @RequestParam("consent") boolean consent) {
        GenerationJob job = service.create(image, name, age, occupation, consent);
        return ResponseEntity.accepted().body(Map.of(
                "id", job.getId(), "status", job.getStatus(), "message", job.getMessage()));
    }

    @GetMapping("/generations/{id}")
    public ResponseEntity<?> status(@PathVariable String id) {
        GenerationJob job = service.find(id);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", job.getId(), "status", job.getStatus(), "message", job.getMessage()));
    }

    @GetMapping("/generations/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        GenerationJob job = service.find(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!"COMPLETED".equals(job.getStatus()) || job.getImage() == null) return ResponseEntity.status(409).build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"future-self.png\"")
                .body(job.getImage());
    }

    @DeleteMapping("/generations/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
