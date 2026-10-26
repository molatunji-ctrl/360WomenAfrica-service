package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class GenerationService {

    private static final String OPENAI_EDIT_URL =
            "https://api.openai.com/v1/images/edits";

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final RestClient openAiClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    private final Map<String, GenerationJob> jobs =
            new ConcurrentHashMap<>();

    private final String model;
    private final BufferedImage frame;
    private final String quality;

    public GenerationService(
            @Value("${app.openai.api-key:}") String apiKey,
            @Value("${app.openai.model:gpt-image-1.5}") String model,
            @Value("${app.openai.quality:low}") String quality,
            @Value("${app.openai.timeout-seconds:240}") int timeoutSeconds,
            ObjectMapper objectMapper
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured."
            );
        }

        if (timeoutSeconds < 30) {
            throw new IllegalArgumentException(
                    "OpenAI timeout must be at least 30 seconds."
            );
        }

        this.model = model;

        // Frame is bundled in src/main/resources/frame.png (real transparent window).
        try (var in = GenerationService.class.getResourceAsStream("/frame.png")) {
            if (in == null) {
                throw new IllegalStateException("frame.png is missing from resources.");
            }
            this.frame = ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load frame.png", e);
        }
        this.quality = quality;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(20_000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        this.openAiClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .build();

        this.executor = Executors.newFixedThreadPool(6);

        System.out.println(
                "[FutureSelf] GenerationService initialized."
        );
    }

    /**
     * Validate the submission, register a job, and begin processing
     * asynchronously.
     */
    public GenerationJob create(
            MultipartFile image,
            String name,
            String age,
            String occupation,
            String consent
    ) {
        validateSubmission(image, name, age, occupation, consent);

        try {
            byte[] uploadedBytes = image.getBytes();

            BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(uploadedBytes)
            );

            if (decoded == null) {
                throw new IllegalArgumentException(
                        "The uploaded file is not a supported image."
                );
            }

            if (decoded.getWidth() < 256 || decoded.getHeight() < 256) {
                throw new IllegalArgumentException(
                        "Please upload a clearer image at least 256 x 256 pixels."
                );
            }

            String contentType = image.getContentType();
            String extension = contentType != null
                    && contentType.toLowerCase(Locale.ROOT).contains("png")
                    ? "png"
                    : "jpg";

            String id = UUID.randomUUID().toString();
            GenerationJob job = new GenerationJob(id);

            jobs.put(id, job);

            System.out.println(
                    "[FutureSelf] Generation started: " + id
            );

            executor.submit(() -> runGeneration(
                    job,
                    uploadedBytes,
                    extension,
                    safeText(name, 100),
                    safeText(age, 20),
                    safeText(occupation, 120)
            ));

            return job;

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "The uploaded image could not be read."
            );
        }
    }

    private void validateSubmission(
            MultipartFile image,
            String name,
            String age,
            String occupation,
            String consent
    ) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please upload a photograph."
            );
        }

        if (image.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "The image must be 10 MB or smaller."
            );
        }

        String contentType = image.getContentType();

        if (contentType == null
                || !(contentType.equalsIgnoreCase("image/jpeg")
                || contentType.equalsIgnoreCase("image/png"))) {
            throw new IllegalArgumentException(
                    "Only JPEG and PNG images are supported."
            );
        }

        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException(
                    "Please provide a valid name."
            );
        }

        if (age == null || age.isBlank()) {
            throw new IllegalArgumentException(
                    "Please provide your age."
            );
        }

        try {
            int parsedAge = Integer.parseInt(age.trim());

            if (parsedAge < 18 || parsedAge > 110) {
                throw new IllegalArgumentException(
                        "Age must be between 18 and 110."
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Please enter a valid age."
            );
        }

        if (occupation != null && occupation.length() > 120) {
            throw new IllegalArgumentException(
                    "Occupation must be 120 characters or fewer."
            );
        }

        if (!"true".equalsIgnoreCase(consent)) {
            throw new IllegalArgumentException(
                    "Consent is required to process your photograph."
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

        if (!"COMPLETED".equals(job.getStatus())) {
            throw new IllegalStateException(
                    "The portrait is not ready yet."
            );
        }

        byte[] result = job.getImage();

        if (result == null || result.length == 0) {
            throw new IllegalStateException(
                    "The completed portrait is unavailable."
            );
        }

        return result;
    }

    public void delete(String id) {
        GenerationJob job = jobs.remove(id);

        if (job != null) {
            job.delete();

            System.out.println(
                    "[FutureSelf] Generation deleted: " + id
            );
        }
    }

    private void runGeneration(
            GenerationJob job,
            byte[] uploadedBytes,
            String extension,
            String name,
            String age,
            String occupation
    ) {
        long start = System.nanoTime();
        String id = job.getId();

        try {
            job.processing();

            System.out.println(
                    "[FutureSelf] Sending image to OpenAI: " + id
            );

            byte[] portrait = generatePortrait(
                    uploadedBytes,
                    extension,
                    name,
                    age,
                    occupation
            );

            System.out.println(
                    "[FutureSelf] OpenAI response received: "
                            + id + ", elapsed=" + elapsedSeconds(start) + "s"
            );

            byte[] finalImage = applyFrame(portrait);

            if (!"DELETED".equals(job.getStatus())) {
                job.complete(finalImage);
            }

            System.out.println(
                    "[FutureSelf] Generation completed: "
                            + id + ", total=" + elapsedSeconds(start) + "s"
            );

        } catch (RestClientResponseException e) {
            System.err.println(
                    "[FutureSelf] OpenAI HTTP error: "
                            + id + ", status=" + e.getStatusCode()
                            + ", response=" + sanitizeError(e.getResponseBodyAsString())
            );

            job.fail(
                    "We could not generate your portrait. Please try again later."
            );

        } catch (Exception e) {
            System.err.println(
                    "[FutureSelf] Generation failed: "
                            + id + ", type=" + e.getClass().getSimpleName()
                            + ", message=" + sanitizeError(e.getMessage())
            );

            job.fail(
                    "Your portrait could not be completed. Please try again."
            );
        }
    }

    private byte[] generatePortrait(
            byte[] uploadedBytes,
            String extension,
            String name,
            String age,
            String occupation
    ) throws IOException {

        String prompt = buildPrompt(name, age, occupation);

        ByteArrayResource imageResource = new ByteArrayResource(
                uploadedBytes
        ) {
            @Override
            public String getFilename() {
                return "reference." + extension;
            }
        };

        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("model", model);
        body.add("prompt", prompt);
        body.add("size", "1024x1536");
        body.add("quality", quality);
        body.add("image", imageResource);

        JsonNode response = openAiClient.post()
                .uri(OPENAI_EDIT_URL)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null
                || !response.has("data")
                || !response.get("data").isArray()
                || response.get("data").isEmpty()) {
            throw new IllegalStateException(
                    "OpenAI returned no generated image."
            );
        }

        JsonNode first = response.get("data").get(0);
        JsonNode encoded = first.get("b64_json");

        if (encoded == null || encoded.asText().isBlank()) {
            throw new IllegalStateException(
                    "OpenAI returned no image data."
            );
        }

        byte[] bytes = Base64.getDecoder().decode(encoded.asText());

        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(bytes)
        );

        if (decoded == null) {
            throw new IllegalStateException(
                    "The generated portrait could not be decoded."
            );
        }

        return bytes;
    }

    private String buildPrompt(
            String name,
            String age,
            String occupation
    ) {
        String career = occupation == null || occupation.isBlank()
                ? "a highly successful company director and CEO"
                : "a highly successful professional working as " + occupation;

        return """
            Create a photorealistic, premium corporate editorial portrait
            of the person in the supplied reference photograph, imagining
            them approximately ten years in the future.

            IDENTITY:
            Use the uploaded photograph as the primary identity reference.
            Preserve the person's recognizable face, facial structure,
            skin tone, ethnicity, eyes, nose, lips, jawline, and overall
            likeness. Do not replace the person with a generic model.
            Use subtle, believable age progression, not exaggerated aging.

            FUTURE CAREER:
            Portray the person as %s.
            The result should communicate confidence, intelligence,
            achievement, sophistication, leadership, and approachability.

            WARDROBE:
            Premium tailored executive clothing appropriate for a successful
            African business leader. Refined accessories, elegant styling,
            natural proportions, and realistic materials.

            ENVIRONMENT:
            A luxurious, believable executive office approximately ten years
            in the future. Floor-to-ceiling glass windows, an international
            city skyline, a premium executive desk, dark wood, glass,
            brushed metal, subtle African luxury design influences, warm
            architectural lighting, and restrained modern technology.

            COMPOSITION:
            A professional, camera-facing medium-wide executive portrait.
            The person may be seated confidently behind the desk.
            Keep the head, face, shoulders, and important body features
            within the image. The office and desk should remain visible.
            Use natural anatomy and realistic hands.
            Vertical portrait orientation (2:3).

            LIGHTING AND STYLE:
            Photorealistic luxury corporate magazine photography,
            realistic skin texture, cinematic warm golden highlights,
            balanced contrast, believable reflections, high detail,
            natural facial features, and premium international editorial
            quality.

            STRICT EXCLUSIONS:
            Do not add text, a nameplate, a logo, a watermark, a border,
            conference branding, captions, graphic overlays, or a frame.
            Do not invent text or make the image science-fictional.
            Generate only the portrait and office environment.

            The final image should communicate:
            "Where could this person be ten years from now?"

            Participant name for contextual reference: %s.
            Current age supplied by participant: %s.
            Future career description: %s.
            """.formatted(
                career,
                name,
                age,
                career
        );
    }

    // Transparent photo window inside frame.png (pixels).
    private static final int WIN_X = 80;
    private static final int WIN_Y = 216;
    private static final int WIN_W = 862;
    private static final int WIN_H = 1029;

    private byte[] applyFrame(byte[] portraitBytes) throws IOException {
        BufferedImage portrait = ImageIO.read(new ByteArrayInputStream(portraitBytes));
        if (portrait == null) {
            throw new IllegalStateException("The generated portrait is invalid.");
        }

        BufferedImage canvas = new BufferedImage(
                frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            // Cover the photo window, centered, clipped to it.
            double scale = Math.max((double) WIN_W / portrait.getWidth(),
                    (double) WIN_H / portrait.getHeight());
            int sw = (int) Math.ceil(portrait.getWidth() * scale);
            int sh = (int) Math.ceil(portrait.getHeight() * scale);
            int x = WIN_X + (WIN_W - sw) / 2;
            int y = WIN_Y + (WIN_H - sh) / 2;

            g.setClip(WIN_X, WIN_Y, WIN_W, WIN_H);
            g.drawImage(portrait, x, y, sw, sh, null);
            g.setClip(null);
            g.drawImage(frame, 0, 0, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", out)) {
            throw new IllegalStateException("Could not export the final portrait as PNG.");
        }
        return out.toByteArray();
    }

    private static String safeText(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();

        return cleaned.length() <= maxLength
                ? cleaned
                : cleaned.substring(0, maxLength);
    }

    private static long elapsedSeconds(long startNanos) {
        return Duration.ofNanos(
                System.nanoTime() - startNanos
        ).toSeconds();
    }

    private static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "No additional details.";
        }

        String cleaned = message
                .replaceAll("sk-[A-Za-z0-9_-]{10,}", "[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ");

        return cleaned.length() > 500
                ? cleaned.substring(0, 500)
                : cleaned;
    }
}