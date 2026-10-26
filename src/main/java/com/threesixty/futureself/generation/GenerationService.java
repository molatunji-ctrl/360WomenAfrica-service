
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.multipart.MultipartFile;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.time.Duration;
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
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println(
                    "[FutureSelf] WARNING: OPENAI_API_KEY is not configured."
            );
        }

        this.objectMapper = objectMapper;
        this.model = model;

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        // Maximum time to establish the connection.
        requestFactory.setConnectTimeout(Duration.ofSeconds(20));

        // Maximum wait for the HTTP response.
        // This prevents a request from waiting indefinitely.
        requestFactory.setReadTimeout(Duration.ofSeconds(240));

        this.openAiClient = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .build();
    }

    /**
     * Validate the request, create a job, and submit it
     * to the background executor.
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

        if (contentType == null
                || !(contentType.equalsIgnoreCase("image/jpeg")
                || contentType.equalsIgnoreCase("image/png"))) {
            throw new IllegalArgumentException(
                    "Please upload a JPG or PNG photograph."
            );
        }

        try {
            byte[] uploadedBytes = image.getBytes();

            BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(uploadedBytes)
            );

            if (decoded == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a valid image."
                );
            }

            String id = UUID.randomUUID().toString();

            GenerationJob job = new GenerationJob(id);
            jobs.put(id, job);

            String safeName = name.trim();
            String safeOccupation = occupation.trim();
            String safeContentType = contentType;

            executor.submit(() -> runGeneration(
                    id,
                    uploadedBytes,
                    safeContentType,
                    safeName,
                    age,
                    safeOccupation
            ));

            System.out.println(
                    "[FutureSelf] Job queued: " + id
            );

            return job;

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "The uploaded photograph could not be read."
            );
        }
    }

    /**
     * Find a generation job by ID.
     */
    public GenerationJob find(String id) {
        GenerationJob job = jobs.get(id);

        if (job == null || "DELETED".equals(job.getStatus())) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        return job;
    }

    /**
     * Return the final composited PNG.
     */
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

    /**
     * Remove a job from the in-memory store.
     */
    public void delete(String id) {
        GenerationJob job = jobs.remove(id);

        if (job == null) {
            throw new IllegalArgumentException(
                    "Generation not found."
            );
        }

        job.delete();

        System.out.println(
                "[FutureSelf] Job deleted: " + id
        );
    }

    /**
     * Generate the portrait, then apply the official frame.
     */
    private void runGeneration(
            String id,
            byte[] uploadedBytes,
            String contentType,
            String name,
            int age,
            String occupation
    ) {
        long startedAt = System.nanoTime();

        System.out.println(
                "[FutureSelf] Generation started: " + id
        );

        GenerationJob job = jobs.get(id);

        if (job == null) {
            System.out.println(
                    "[FutureSelf] Job no longer exists: " + id
            );
            return;
        }

        try {
            job.processing();

            String prompt = buildPrompt(
                    name,
                    age,
                    occupation
            );

            MultipartBodyBuilder body =
                    new MultipartBodyBuilder();

            body.part("model", model);
            body.part("prompt", prompt);
            body.part("size", "1024x1024");

            // Test medium quality to balance image quality and speed.
            body.part("quality", "medium");

            MediaType uploadType =
                    MediaType.parseMediaType(contentType);

            body.part(
                    "image",
                    new ByteArrayResource(uploadedBytes) {
                        @Override
                        public String getFilename() {
                            return contentType.equalsIgnoreCase(
                                    "image/png"
                            )
                                    ? "reference.png"
                                    : "reference.jpg";
                        }
                    }
            ).contentType(uploadType);

            // Measure time spent waiting for OpenAI.
            long openAiStartedAt = System.nanoTime();

            System.out.println(
                    "[FutureSelf] Sending image to OpenAI: " + id
            );

            String response = openAiClient.post()
                    .uri("/images/edits")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(String.class);

            System.out.println(
                    "[FutureSelf] OpenAI response received in "
                            + elapsedSeconds(openAiStartedAt)
                            + " seconds. Job: " + id
            );

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "The image service returned an empty response."
                );
            }

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

            byte[] portraitBytes =
                    Base64.getDecoder().decode(base64);

            BufferedImage portrait = ImageIO.read(
                    new ByteArrayInputStream(portraitBytes)
            );

            if (portrait == null) {
                throw new IllegalStateException(
                        "The generated portrait could not be decoded."
                );
            }

            // Measure time spent applying the official frame.
            long frameStartedAt = System.nanoTime();

            System.out.println(
                    "[FutureSelf] Applying conference frame: " + id
            );

            byte[] finalImage = applyFrame(portrait);

            System.out.println(
                    "[FutureSelf] Frame compositing took "
                            + elapsedSeconds(frameStartedAt)
                            + " seconds. Job: " + id
            );

            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null
                    && !"DELETED".equals(latestJob.getStatus())) {

                latestJob.complete(finalImage);

                System.out.println(
                        "[FutureSelf] Generation completed in "
                                + elapsedSeconds(startedAt)
                                + " seconds. Job: " + id
                );
            }

        } catch (RestClientResponseException e) {
            System.err.println(
                    "[FutureSelf] OpenAI HTTP error for job "
                            + id
                            + ": status=" + e.getStatusCode()
                            + ", body=" + sanitizeError(
                            e.getResponseBodyAsString()
                    )
            );

            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null) {
                latestJob.fail(
                        "Image generation failed. Please try again."
                );
            }

        } catch (Exception e) {
            System.err.println(
                    "[FutureSelf] Generation error for job "
                            + id + ": " + e.getClass().getSimpleName()
                            + " - " + e.getMessage()
            );

            GenerationJob latestJob = jobs.get(id);

            if (latestJob != null) {
                latestJob.fail(
                        "We could not create your portrait. Please try again."
                );
            }
        }
    }

    /**
     * Prompt for the future-self portrait.
     */
    private String buildPrompt(
            String name,
            int age,
            String occupation
    ) {
        return """
                Create a premium, photorealistic future-self portrait
                using the uploaded photograph as the primary identity reference.

                Preserve the person's recognizable facial identity, facial
                structure, skin tone, ethnicity, eyes, nose, lips, jawline,
                and distinctive features. The person must remain clearly
                recognizable as the same individual.

                Apply subtle, believable age progression of approximately
                ten years. Do not replace the person with a generic model.

                Future professional vision:
                The person is an accomplished African business leader,
                company director, founder, or CEO ten years in the future.

                Attendee information for contextual guidance:
                Name: %s
                Current age: %d
                Current occupation: %s

                Dress the person in sophisticated premium executive fashion
                suitable for an internationally successful African business
                leader. Use a tailored suit or elegant executive outfit,
                refined accessories, realistic fabric, and natural proportions.

                Place the person in a luxurious but believable futuristic
                executive office with floor-to-ceiling glass windows,
                a modern city skyline, premium dark wood, glass, brushed
                metal, restrained African-inspired design influences,
                and warm golden architectural lighting.

                Position the person seated confidently behind an executive
                desk, facing the camera. Use a square composition suitable
                for a conference photo frame. Keep the head, face, hair,
                shoulders, and hands away from the image edges.

                Use realistic skin texture, natural facial features,
                accurate anatomy, realistic reflections, controlled contrast,
                and high-end corporate editorial photography.

                The environment must remain believable, not science fiction.

                Generate the portrait photograph only.

                Do not add any text, name, title, company name, logo,
                watermark, border, conference frame, caption, or graphic.

                The official 360 Woman Africa conference frame will be
                composited separately by the backend.
                """.formatted(
                cleanPromptText(name),
                age,
                cleanPromptText(occupation)
        );
    }

    /**
     * Composite the portrait with the official transparent PNG frame.
     */
    private byte[] applyFrame(BufferedImage portrait)
            throws IOException {

        ClassPathResource frameResource =
                new ClassPathResource("360Women.png");

        if (!frameResource.exists()) {
            throw new IllegalStateException(
                    "Missing src/main/resources/360Women.png"
            );
        }

        BufferedImage frame;

        try (var inputStream = frameResource.getInputStream()) {
            frame = ImageIO.read(inputStream);
        }

        if (frame == null) {
            throw new IllegalStateException(
                    "360Women.png is not a valid PNG image."
            );
        }

        if (!frame.getColorModel().hasAlpha()) {
            throw new IllegalStateException(
                    "360Women.png must have an alpha channel and a "
                            + "transparent central photo window."
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

            // Scale proportionally to cover the entire canvas.
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

            // Preserve the original frame artwork by drawing it on top.
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(frame, 0, 0, null);

        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        boolean pngWritten = ImageIO.write(
                output,
                "png",
                outputStream
        );

        if (!pngWritten) {
            throw new IOException(
                    "Could not encode the final PNG image."
            );
        }

        return outputStream.toByteArray();
    }

    private long elapsedSeconds(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000_000L;
    }

    private String cleanPromptText(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Avoid printing an entire API error response into the logs.
     */
    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "No response body";
        }

        String cleaned = error
                .replaceAll(
                        "(?i)(sk-[A-Za-z0-9_-]{10,})",
                        "[REDACTED]"
                )
                .replaceAll("[\\r\\n\\t]", " ");

        return cleaned.length() > 1000
                ? cleaned.substring(0, 1000)
                : cleaned;
    }
}
