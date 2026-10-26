
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GenerationService {

    private static final long MAX_IMAGE_SIZE =
            10L * 1024L * 1024L;

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private final ConcurrentHashMap<String, GenerationJob> jobs =
            new ConcurrentHashMap<>();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(3);

    public GenerationService(
            @Value("${app.openai.api-key:}") String apiKey,
            @Value("${app.openai.model:gpt-image-1.5}") String model,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .build();
    }

    /**
     * Validates the request and starts portrait generation.
     */
    public GenerationJob create(
            MultipartFile image,
            String name,
            Integer age,
            String occupation,
            Boolean consent
    ) {
        if (!Boolean.TRUE.equals(consent)) {
            throw new IllegalArgumentException(
                    "Consent is required before generating a portrait."
            );
        }

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please upload a photo."
            );
        }

        if (image.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException(
                    "The uploaded image must not exceed 10 MB."
            );
        }

        if (age == null || age < 18 || age > 110) {
            throw new IllegalArgumentException(
                    "Age must be between 18 and 110."
            );
        }

        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException(
                    "Please provide a valid name."
            );
        }

        if (occupation == null
                || occupation.isBlank()
                || occupation.length() > 120) {
            throw new IllegalArgumentException(
                    "Please provide a valid occupation."
            );
        }

        String contentType = image.getContentType();

        if (contentType == null
                || !contentType.toLowerCase().startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Please upload a valid image file."
            );
        }

        String filename = getFilename(image);

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "The image generation service is not configured."
            );
        }

        final byte[] uploadedImage;

        try {
            uploadedImage = image.getBytes();

            BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(uploadedImage)
            );

            if (decoded == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a readable image."
                );
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(
                    "Unable to read the uploaded image."
            );
        }

        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id);

        jobs.put(id, job);

        executor.submit(() -> runGeneration(
                job,
                uploadedImage,
                filename,
                name.trim(),
                age,
                occupation.trim()
        ));

        return job;
    }

    /**
     * Finds a generation job by ID.
     */
    public GenerationJob find(String id) {
        GenerationJob job = jobs.get(id);

        if (job == null) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        return job;
    }

    /**
     * Deletes a generation job.
     */
    public void delete(String id) {
        GenerationJob job = jobs.remove(id);

        if (job == null) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        job.delete();
    }

    /**
     * Generates the complete future-self portrait.
     * No separate frame image is loaded or composited.
     */
    private void runGeneration(
            GenerationJob job,
            byte[] uploadedImage,
            String filename,
            String name,
            Integer age,
            String occupation
    ) {
        job.processing();

        try {
            String prompt = buildPrompt(name, age, occupation);

            MultiValueMap<String, Object> form =
                    new LinkedMultiValueMap<>();

            form.add("model", model);
            form.add("prompt", prompt);
            form.add("size", "1024x1024");

            ByteArrayResource imageResource =
                    new ByteArrayResource(uploadedImage) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    };

            form.add("image", imageResource);

            JsonNode response = restClient.post()
                    .uri("/images/edits")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null
                    || !response.has("data")
                    || !response.get("data").isArray()
                    || response.get("data").isEmpty()) {
                throw new IllegalStateException(
                        "The image service returned no generated image."
                );
            }

            JsonNode firstImage = response.get("data").get(0);
            JsonNode base64Node = firstImage.get("b64_json");

            if (base64Node == null
                    || base64Node.isNull()
                    || base64Node.asText().isBlank()) {
                throw new IllegalStateException(
                        "The image service did not return image data."
                );
            }

            byte[] generatedImage = Base64.getDecoder().decode(
                    base64Node.asText()
            );

            // Validate the returned image before completing the job.
            BufferedImage decodedImage = ImageIO.read(
                    new ByteArrayInputStream(generatedImage)
            );

            if (decodedImage == null) {
                throw new IllegalStateException(
                        "The generated image could not be decoded."
                );
            }

            // Save the complete AI-generated image directly.
            job.complete(generatedImage);

        } catch (RestClientResponseException e) {
            String error = sanitizeError(
                    e.getResponseBodyAsString()
            );

            System.err.println(
                    "OpenAI image generation failed. HTTP "
                            + e.getStatusCode().value()
                            + ": " + error
            );

            job.fail(
                    "Image generation failed. Please try again later."
            );

        } catch (Exception e) {
            System.err.println(
                    "Portrait generation failed: " + e.getMessage()
            );

            job.fail(
                    "We could not create your portrait. Please try again."
            );
        }
    }

    /**
     * Prompt for generating the portrait and conference frame together.
     */
    private String buildPrompt(
            String name,
            Integer age,
            String occupation
    ) {
        return """
                Create a premium, photorealistic AI Future Self
                portrait for the 360 Woman Africa Conference 2026.

                PERSON DETAILS:
                Name: %s
                Current age: %d
                Occupation or ambition: %s

                IDENTITY:
                Use the uploaded photograph as the person's identity
                reference. Preserve recognisable facial features,
                skin tone, ethnicity, and distinctive appearance.
                The result must remain recognisably the same person.

                FUTURE SELF:
                Show a believable version of the person approximately
                ten years into the future. Present her as confident,
                accomplished, ambitious, empowered, and professionally
                successful. Reflect her occupation through elegant
                clothing and appropriate professional styling.

                COMPOSITION:
                Generate the portrait and its conference frame together
                as ONE complete, finished image.

                Create a premium conference portrait design featuring
                an elegant purple and gold colour palette, refined
                decorative borders, tasteful African-inspired geometric
                accents, balanced spacing, and professional lighting.

                Keep the woman's face clearly visible and near the
                centre. Make the frame symmetrical, polished, and
                visually balanced. Do not let decorations cover her face.

                BRANDING:
                The event is called 360 Woman Africa Conference 2026.
                Leave a clean, uncluttered area suitable for conference
                branding. Avoid inventing logos, sponsor names, or
                additional event information.

                QUALITY:
                Photorealistic skin texture, natural anatomy, realistic
                hands, premium editorial photography, sophisticated
                colour grading, sharp facial details, and professional
                art direction.

                Avoid clutter, distorted borders, extra people,
                malformed facial features, watermarks, and gibberish text.

                The final result must be a single image containing both
                the woman and the designed conference frame. Do not
                output a separate frame, a separate portrait, or a
                transparent overlay.
                """.formatted(
                cleanPromptText(name),
                age,
                cleanPromptText(occupation)
        );
    }

    /**
     * Determines a safe filename for the uploaded image.
     */
    private String getFilename(MultipartFile image) {
        String contentType = image.getContentType();

        if (contentType != null) {
            String type = contentType.toLowerCase();

            if (type.contains("png")) {
                return "uploaded-image.png";
            }

            if (type.contains("webp")) {
                return "uploaded-image.webp";
            }

            if (type.contains("jpeg") || type.contains("jpg")) {
                return "uploaded-image.jpg";
            }
        }

        String originalFilename = image.getOriginalFilename();

        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase();

            if (lower.endsWith(".png")) {
                return "uploaded-image.png";
            }

            if (lower.endsWith(".webp")) {
                return "uploaded-image.webp";
            }

            if (lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg")) {
                return "uploaded-image.jpg";
            }
        }

        throw new IllegalArgumentException(
                "Please upload a PNG, JPEG, or WebP image."
        );
    }

    /**
     * Cleans prompt values before inserting them into the prompt.
     */
    private String cleanPromptText(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    /**
     * Redacts credentials and limits logged API error details.
     */
    private String sanitizeError(String response) {
        if (response == null || response.isBlank()) {
            return "No error details were returned.";
        }

        String sanitized = response
                .replaceAll(
                        "(?i)Bearer\\s+\\S+",
                        "Bearer [REDACTED]"
                )
                .replaceAll(
                        "sk-[A-Za-z0-9_-]+",
                        "[REDACTED]"
                );

        return sanitized.substring(
                0,
                Math.min(sanitized.length(), 2000)
        );
    }
}
