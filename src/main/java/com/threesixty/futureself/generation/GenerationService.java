
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GenerationService {

    private final RestClient openAiClient;
    private final ObjectMapper objectMapper;
    private final String model;

    private final Map<String, GenerationJob> jobs =
            new ConcurrentHashMap<>();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2);

    public GenerationService(
            @Value("${app.openai.api-key:}") String apiKey,
            @Value("${app.openai.model:gpt-image-1.5}") String model,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.model = model;

        this.openAiClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .build();
    }

    public GenerationJob create(
            MultipartFile image,
            String name,
            Integer age,
            String occupation,
            Boolean consent
    ) {
        if (!Boolean.TRUE.equals(consent)) {
            throw new IllegalArgumentException(
                    "Consent is required before generating an image."
            );
        }

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please upload a photograph."
            );
        }

        if (image.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "The uploaded image must be 10 MB or smaller."
            );
        }

        if (!StringUtils.hasText(name) || name.length() > 100) {
            throw new IllegalArgumentException(
                    "Please provide a valid name."
            );
        }

        if (age == null || age < 18 || age > 110) {
            throw new IllegalArgumentException(
                    "Age must be between 18 and 110."
            );
        }

        if (!StringUtils.hasText(occupation)
                || occupation.length() > 120) {
            throw new IllegalArgumentException(
                    "Please provide a valid occupation."
            );
        }

        String contentType = image.getContentType();

        if (contentType == null ||
                !(contentType.equalsIgnoreCase("image/jpeg")
                        || contentType.equalsIgnoreCase("image/png")
                        || contentType.equalsIgnoreCase("image/webp"))) {
            throw new IllegalArgumentException(
                    "Upload a JPG, PNG, or WebP photograph."
            );
        }

        try {
            byte[] uploadedBytes = image.getBytes();

            BufferedImage decoded =
                    ImageIO.read(new ByteArrayInputStream(uploadedBytes));

            if (decoded == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a valid supported image."
                );
            }

            String id = UUID.randomUUID().toString();
            GenerationJob job = new GenerationJob(id);

            jobs.put(id, job);

            executor.submit(() -> runGeneration(
                    id,
                    uploadedBytes,
                    contentType,
                    name.trim(),
                    age,
                    occupation.trim()
            ));

            return job;

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "The uploaded photograph could not be read."
            );
        }
    }

    public GenerationJob find(String id) {
        GenerationJob job = jobs.get(id);

        if (job == null || "DELETED".equals(job.getStatus())) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        return job;
    }

    public byte[] getImage(String id) {
        GenerationJob job = find(id);

        if (!"COMPLETED".equals(job.getStatus())
                || job.getImage() == null) {
            throw new IllegalStateException(
                    "The final image is not ready yet."
            );
        }

        return job.getImage();
    }

    public void delete(String id) {
        GenerationJob job = jobs.remove(id);

        if (job == null) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        job.delete();
    }

    private void runGeneration(
            String id,
            byte[] uploadedBytes,
            String contentType,
            String name,
            int age,
            String occupation
    ) {
        GenerationJob job = jobs.get(id);

        if (job == null) {
            return;
        }

        try {
            job.processing();

            String prompt = buildPrompt(name, age, occupation);

            MultipartBodyBuilder body = new MultipartBodyBuilder();

            body.part("model", model);
            body.part("prompt", prompt);
            body.part("size", "1024x1024");

            MediaType uploadType = MediaType.parseMediaType(contentType);

            body.part(
                    "image",
                    new ByteArrayResource(uploadedBytes) {
                        @Override
                        public String getFilename() {
                            return contentType.equalsIgnoreCase("image/png")
                                    ? "reference.png"
                                    : contentType.equalsIgnoreCase("image/webp")
                                    ? "reference.webp"
                                    : "reference.jpg";
                        }
                    }
            ).contentType(uploadType);

            String response = openAiClient.post()
                    .uri("/images/edits")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String base64 = root.path("data")
                    .path(0)
                    .path("b64_json")
                    .asText("");

            if (base64.isBlank()) {
                throw new IllegalStateException(
                        "The image service returned no portrait."
                );
            }

            byte[] portraitBytes = Base64.getDecoder().decode(base64);

            BufferedImage portrait = ImageIO.read(
                    new ByteArrayInputStream(portraitBytes)
            );

            if (portrait == null) {
                throw new IllegalStateException(
                        "The generated portrait could not be decoded."
                );
            }

            byte[] finalImage = applyFrame(portrait);

            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null
                    && !"DELETED".equals(latestJob.getStatus())) {
                latestJob.complete(finalImage);
            }

        } catch (RestClientResponseException e) {
            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null) {
                latestJob.fail(
                        "Image generation failed. Please try again later."
                );
            }

            System.err.println(
                    "OpenAI API error: HTTP " + e.getStatusCode()
                            + " - " + e.getResponseBodyAsString()
            );

        } catch (Exception e) {
            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null) {
                latestJob.fail(
                        "We could not create your portrait. Please try again."
                );
            }

            System.err.println(
                    "Generation error: " + e.getMessage()
            );
        }
    }

    private String buildPrompt(
            String name,
            int age,
            String occupation
    ) {
        return """
                Create a premium, photorealistic future-self portrait
                using the uploaded photograph as the primary identity reference.

                Preserve the person's recognizable facial identity, face shape,
                eyes, nose, lips, skin tone, ethnicity, and distinctive features.
                The person must remain recognizable as the same individual.

                Depict a believable, subtle age progression of approximately
                ten years. Do not replace the person with a generic model.

                Future professional context:
                The person is an accomplished African business leader,
                executive, company director, or CEO ten years in the future.

                Current attendee-provided details for contextual guidance:
                Name: %s
                Current age: %d
                Current occupation: %s

                Create a sophisticated, tailored executive outfit and a
                believable luxury office with floor-to-ceiling windows,
                a modern city skyline, warm golden architectural lighting,
                dark wood, glass, brushed metal, and restrained African-inspired
                design influences.

                Position the person seated confidently behind an executive desk,
                facing the camera. Use a square composition suitable for a
                conference portrait frame. Keep the head, hair, face, shoulders,
                and hands away from image edges.

                Use realistic skin texture, natural proportions, accurate hands,
                realistic materials, professional corporate editorial lighting,
                and high-end photographic quality.

                The office should be futuristic but believable, not science fiction.

                Generate the portrait photograph only.
                Do not add any text, person's name, job title, company name,
                logo, watermark, border, conference frame, caption, or graphic.

                The official conference frame will be applied separately.
                """.formatted(
                cleanPromptText(name),
                age,
                cleanPromptText(occupation)
        );
    }

    private String cleanPromptText(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private byte[] applyFrame(BufferedImage portrait)
            throws IOException {

        ClassPathResource frameResource =
                new ClassPathResource("360Women.png");

        if (!frameResource.exists()) {
            throw new IllegalStateException(
                    "Missing src/main/resources/360Women.png"
            );
        }

        BufferedImage frame = ImageIO.read(frameResource.getInputStream());

        if (frame == null) {
            throw new IllegalStateException(
                    "360Women.png is not a valid image."
            );
        }

        if (!frame.getColorModel().hasAlpha()) {
            throw new IllegalStateException(
                    "360Women.png must have transparency in its central "
                            + "photo window. Prepare a transparent PNG overlay first."
            );
        }

        int outputWidth = frame.getWidth();
        int outputHeight = frame.getHeight();

        BufferedImage output = new BufferedImage(
                outputWidth,
                outputHeight,
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D graphics = output.createGraphics();

        try {
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

            // Scale portrait proportionally to cover the canvas.
            double scale = Math.max(
                    (double) outputWidth / portrait.getWidth(),
                    (double) outputHeight / portrait.getHeight()
            );

            int scaledWidth = (int) Math.round(
                    portrait.getWidth() * scale
            );
            int scaledHeight = (int) Math.round(
                    portrait.getHeight() * scale
            );

            int x = (outputWidth - scaledWidth) / 2;
            int y = (outputHeight - scaledHeight) / 2;

            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(
                    portrait,
                    x,
                    y,
                    scaledWidth,
                    scaledHeight,
                    null
            );

            // Draw the official frame over the portrait.
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(frame, 0, 0, null);

        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        ImageIO.write(output, "png", outputStream);

        return outputStream.toByteArray();
    }
}
