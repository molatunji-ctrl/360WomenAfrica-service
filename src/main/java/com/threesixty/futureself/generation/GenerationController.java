
package com.threesixty.futureself.generation;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/generations")
public class GenerationController {

    private final GenerationService generationService;

    public GenerationController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationJob> create(
            @RequestParam("image") MultipartFile image,
            @RequestParam("name") String name,
            @RequestParam("age") String age,
            @RequestParam("occupation") String occupation,
            @RequestParam("consent") String consent) {

        GenerationJob job = generationService.create(
                image, name, age, occupation, consent
        );

        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GenerationJob> getGeneration(
            @PathVariable("id") String id) {

        return ResponseEntity.ok(generationService.find(id));
    }

    @GetMapping(value = "/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getImage(
            @PathVariable("id") String id) {

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(generationService.getImage(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGeneration(
            @PathVariable("id") String id) {

        generationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
