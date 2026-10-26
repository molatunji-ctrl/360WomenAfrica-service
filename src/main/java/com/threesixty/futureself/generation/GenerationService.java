
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
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
import java.io.InputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GenerationService {

    private static final Logger log =
            LoggerFactory.getLogger(GenerationService.class);

    private static final String FRAME_RESOURCE =
            "FILETER 360 WOMAN.png";

    private static final String GENERIC_FAILURE_MESSAGE =
            "Image generation failed. Check backend logs and OpenAI model access, then try again.";

    private final ConcurrentHashMap<String, GenerationJob> jobs =
            new ConcurrentHashMap<>();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(3);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public GenerationService(
            @Value("${app.openai.api-key:}") String apiKey,
            @Value("${app.openai.model:gpt-image-1.5}") String model,
            ObjectMapper mapper) {

        this.apiKey = apiKey;
        this.model = model;
        this.mapper = mapper;

        this.restClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .build();
    }

    public GenerationJob create(
            MultipartFile image,
            String name,
            Integer age,
            String occupation,
            Boolean consent) {

        if (!Boolean.TRUE.equals(consent)) {
            throw new IllegalArgumentException(
                    "Consent is required before image generation.");
        }

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please provide an image.");
        }

        if (image.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "The uploaded image must not exceed 10 MB.");
        }

        if (age == null || age < 18 || age > 110) {
            throw new IllegalArgumentException(
                    "Age must be between 18 and 110.");
        }

        if (occupation == null
                || occupation.isBlank()
                || occupation.length() > 120) {
            throw new IllegalArgumentException(
                    "Please provide an occupation of no more than 120 characters.");
        }

        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException(
                    "Please provide a name of no more than 100 characters.");
        }

        String contentType = image.getContentType();

        if (contentType == null
                || !contentType.toLowerCase().startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Please upload a valid image.");
        }

        // Validate the supported input image format.
        getFilename(contentType);

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "The OpenAI API key is not configured on the backend.");
        }

        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id);

        jobs.put(id, job);

        try {
            byte[] originalImage = image.getBytes();
            String prompt = buildPrompt(age, occupation);

            executor.submit(() -> runGeneration(
                    job,
                    originalImage,
                    contentType,
                    prompt
            ));

        } catch (Exception e) {
            jobs.remove(id);
            throw new IllegalStateException(
                    "Could not read the uploaded image.", e);
        }

        return job;
    }

    public GenerationJob find(String id) {
        return jobs.get(id);
    }

    public void delete(String id) {
        GenerationJob job = jobs.remove(id);

        if (job != null) {
            job.delete();
        }
    }

    private String buildPrompt(int age, String occupation) {
        String safeOccupation = occupation
                .replaceAll("[\\r\\n]", " ")
                .trim();

        return """
                Create a premium, photorealistic artistic visualisation
                of the person in the supplied reference photograph.

                Preserve the person's recognizable facial identity,
                facial structure, natural skin tone and distinctive
                features as closely as possible.

                Show the same adult as a plausible, more mature version
                approximately ten years into the future. Current age:
                %d. Approximate future age: %d.

                Profession or aspiration: %s.

                Reflect the profession through tasteful, realistic
                professional clothing, posture and an appropriate setting.
                Create a confident, inspiring portrait suitable for a
                premium African women's leadership conference campaign.

                Use realistic photographic lighting, natural skin texture,
                refined composition and high detail.

                Produce one cohesive portrait. Do not add text, logos,
                watermarks, borders, frames, collages or extra people.
                The conference frame will be added separately by the
                application.

                This image is an artistic aspirational visualisation,
                not a prediction of the person's actual future.
                """.formatted(age, age + 10, safeOccupation);
    }

    private void runGeneration(
            GenerationJob job,
            byte[] originalImage,
            String contentType,
            String prompt) {

        try {
            String filename = getFilename(contentType);

            ByteArrayResource imageResource =
                    new ByteArrayResource(originalImage) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    };

            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(
                    MediaType.parseMediaType(contentType));

            HttpEntity<ByteArrayResource> imageEntity =
                    new HttpEntity<>(imageResource, imageHeaders);

            MultiValueMap<String, Object> body =
                    new LinkedMultiValueMap<>();

            body.add("model", model);
            body.add("prompt", prompt);
            body.add("size", "1024x1024");
            body.add("image", imageEntity);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(
                    MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> request =
                    new HttpEntity<>(body, headers);

            log.info(
                    "Starting image generation. Job ID: {}, model: {}, image type: {}, image size: {} bytes",
                    job.getId(),
                    model,
                    contentType,
                    originalImage.length
            );

            String response = restClient.post()
                    .uri("/images/edits")
                    .headers(h -> h.addAll(headers))
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(response);
            JsonNode data = root.path("data");

            if (!data.isArray()
                    || data.isEmpty()
                    || !data.get(0).hasNonNull("b64_json")) {
                throw new IllegalStateException(
                        "OpenAI returned no generated image.");
            }

            byte[] generatedImage = Base64.getDecoder().decode(
                    data.get(0).get("b64_json").asText()
            );

            // Combine the generated portrait with the conference frame.
            byte[] finalImage = applyFrame(generatedImage);

            job.complete(finalImage);

            log.info(
                    "Image generation and frame overlay completed. Job ID: {}, output size: {} bytes",
                    job.getId(),
                    finalImage.length
            );

        } catch (RestClientResponseException e) {
            log.error(
                    "OpenAI request failed. Job ID: {}, HTTP status: {}, response: {}",
                    job.getId(),
                    e.getStatusCode(),
                    sanitizeError(e.getResponseBodyAsString())
            );

            job.fail(GENERIC_FAILURE_MESSAGE);

        } catch (Exception e) {
            log.error(
                    "Image generation failed. Job ID: {}, error: {}",
                    job.getId(),
                    e.getMessage(),
                    e
            );

            job.fail(GENERIC_FAILURE_MESSAGE);
        }
    }

    /**
     * Places the generated portrait underneath the transparent PNG frame.
     * The output uses the frame's exact width and height.
     */
    private byte[] applyFrame(byte[] generatedImageBytes) throws Exception {

        BufferedImage generatedImage = ImageIO.read(
                new ByteArrayInputStream(generatedImageBytes));

        if (generatedImage == null) {
            throw new IllegalStateException(
                    "The generated image could not be decoded.");
        }

        ClassPathResource frameResource =
                new ClassPathResource(FRAME_RESOURCE);

        if (!frameResource.exists()) {
            throw new IllegalStateException(
                    "Frame file not found in backend resources: "
                            + FRAME_RESOURCE);
        }

        BufferedImage frame;

        try (InputStream input = frameResource.getInputStream()) {
            frame = ImageIO.read(input);
        }

        if (frame == null) {
            throw new IllegalStateException(
                    "The conference frame is not a readable PNG image.");
        }

        int width = frame.getWidth();
        int height = frame.getHeight();

        // Create a canvas with the same dimensions as the frame.
        BufferedImage combined = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D graphics = combined.createGraphics();

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

            /*
             * Cover the full canvas with the portrait while preserving
             * its aspect ratio. This centre-crops if the aspect ratios
             * differ.
             */
            double scale = Math.max(
                    (double) width / generatedImage.getWidth(),
                    (double) height / generatedImage.getHeight()
            );

            int scaledWidth = (int) Math.ceil(
                    generatedImage.getWidth() * scale);

            int scaledHeight = (int) Math.ceil(
                    generatedImage.getHeight() * scale);

            int x = (width - scaledWidth) / 2;
            int y = (height - scaledHeight) / 2;

            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(
                    generatedImage,
                    x,
                    y,
                    scaledWidth,
                    scaledHeight,
                    null
            );

            // Draw the frame on top, preserving its transparency.
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(frame, 0, 0, null);

        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if (!ImageIO.write(combined, "png", output)) {
            throw new IllegalStateException(
                    "Could not encode the final PNG image.");
        }

        return output.toByteArray();
    }

    private String getFilename(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "upload.png";
            case "image/webp" -> "upload.webp";
            case "image/jpeg", "image/jpg" -> "upload.jpg";
            default -> throw new IllegalArgumentException(
                    "Supported image formats are PNG, JPEG and WebP.");
        };
    }

    private String sanitizeError(String response) {
        if (response == null) {
            return "";
        }

        return response
                .replaceAll("(?i)Bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]+", "[REDACTED]")
                .substring(0, Math.min(response.length(), 4000));
    }
}
