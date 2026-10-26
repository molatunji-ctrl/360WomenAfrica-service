
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GenerationService {

    private static final long MAX_IMAGE_SIZE = 10L * 1024L * 1024L;

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
     * Validates the user's request and starts portrait generation.
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

            // Check that the uploaded file can actually be decoded.
            BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(uploadedImage)
            );

            if (decoded == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a readable image."
                );
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Unable to read the uploaded image."
            );
        }

        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id);

        jobs.put(id, job);

        String safeName = name.trim();
        String safeOccupation = occupation.trim();

        executor.submit(() -> runGeneration(
                job,
                uploadedImage,
                contentType,
                filename,
                safeName,
                age,
                safeOccupation
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
     * Deletes a generation job and its generated image.
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
     * Calls OpenAI, composites the frame, and completes the job.
     */
    private void runGeneration(
            GenerationJob job,
            byte[] uploadedImage,
            String contentType,
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

            // Composite the generated portrait with the frame.
            byte[] finalImage = applyFrame(generatedImage);

            // Store the COMBINED image, not the original portrait.
            job.complete(finalImage);

        } catch (RestClientResponseException e) {
            String error = sanitizeError(e.getResponseBodyAsString());

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
     * Instructions for the future-self portrait.
     */
    private String buildPrompt(
            String name,
            Integer age,
            String occupation
    ) {
        return """
                Create a photorealistic, inspiring future-self portrait
                using the uploaded photograph as the identity reference.

                The person's name is %s.
                Their current age is %d.
                Their occupation or ambition is %s.

                Show a believable, confident, successful future version
                of the same person approximately ten years into the future.

                Preserve their recognisable facial identity, skin tone,
                ethnicity, and distinctive facial features. The person
                must remain recognisably the same individual.

                Reflect their occupation through tasteful clothing,
                styling, and subtle professional context where appropriate.

                Use flattering professional portrait lighting, realistic
                skin texture, natural anatomy, and a polished editorial
                photography style.

                Frame the person from the chest or waist up, facing the
                camera, with their face clearly visible and positioned
                near the centre of the image.

                Compose the image as a square portrait suitable for
                placement behind a separate conference photo frame.

                Do not create a photo frame, border, logo, watermark,
                lettering, captions, or decorative overlay. The frame
                will be added separately by the application.

                Do not replace the person with a different individual.
                """.formatted(
                cleanPromptText(name),
                age,
                cleanPromptText(occupation)
        );
    }

    /**
     * Combines the generated portrait with the transparent PNG frame.
     *
     * IMPORTANT:
     * The frame must have a genuinely transparent photo opening.
     */
    private byte[] applyFrame(byte[] generatedImage) throws IOException {

        // Load the AI-generated portrait.
        BufferedImage portrait = ImageIO.read(
                new ByteArrayInputStream(generatedImage)
        );

        if (portrait == null) {
            throw new IOException(
                    "The generated portrait could not be decoded."
            );
        }

        // Load the frame from src/main/resources.
        ClassPathResource frameResource =
                new ClassPathResource("FILETER 360 WOMAN.png");

        if (!frameResource.exists()) {
            throw new IOException(
                    "Frame not found: FILETER 360 WOMAN.png. "
                            + "Place it in src/main/resources."
            );
        }

        BufferedImage frame;

        try (var inputStream = frameResource.getInputStream()) {
            frame = ImageIO.read(inputStream);
        }

        if (frame == null) {
            throw new IOException(
                    "The frame is not a readable PNG image."
            );
        }

        int canvasWidth = frame.getWidth();
        int canvasHeight = frame.getHeight();

        /*
         * Scale the portrait to COVER the entire frame canvas.
         * The image keeps its aspect ratio and is centre-cropped.
         */
        double scale = Math.max(
                (double) canvasWidth / portrait.getWidth(),
                (double) canvasHeight / portrait.getHeight()
        );

        int scaledWidth = (int) Math.ceil(
                portrait.getWidth() * scale
        );

        int scaledHeight = (int) Math.ceil(
                portrait.getHeight() * scale
        );

        BufferedImage output = new BufferedImage(
                canvasWidth,
                canvasHeight,
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D graphics = output.createGraphics();

        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            // STEP 1: Draw the generated portrait FIRST.
            int x = (canvasWidth - scaledWidth) / 2;
            int y = (canvasHeight - scaledHeight) / 2;

            graphics.drawImage(
                    portrait,
                    x,
                    y,
                    scaledWidth,
                    scaledHeight,
                    null
            );

            // STEP 2: Draw the transparent frame ON TOP.
            graphics.setComposite(AlphaComposite.SrcOver);

            graphics.drawImage(
                    frame,
                    0,
                    0,
                    canvasWidth,
                    canvasHeight,
                    null
            );

        } finally {
            graphics.dispose();
        }

        // Export the combined result as PNG to preserve transparency.
        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        boolean written = ImageIO.write(
                output,
                "png",
                outputStream
        );

        if (!written) {
            throw new IOException(
                    "Could not encode the final image as PNG."
            );
        }

        return outputStream.toByteArray();
    }

    /**
     * Determines a filename for the uploaded image.
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
     * Removes newlines and surrounding whitespace from prompt values.
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
