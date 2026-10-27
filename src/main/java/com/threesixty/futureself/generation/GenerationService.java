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
import java.awt.geom.RoundRectangle2D;
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
        boolean hasOccupation = occupation != null && !occupation.isBlank();

        String futureAge;
        try {
            futureAge = String.valueOf(Integer.parseInt(age.trim()) + 10);
        } catch (NumberFormatException e) {
            futureAge = "ten years older than now";
        }

        String career = hasOccupation
                ? "a highly accomplished, senior-level " + occupation
                + " (a leader, owner or top expert in this field)"
                : "a highly successful company director and CEO";

        String environmentRule = hasOccupation
                ? """
                  Design the setting around the profession "%s". Show the
                  real, authentic workplace of a top-level %s: the right
                  location, equipment, tools, props, and details that
                  someone in this field would genuinely use. A viewer
                  should recognise the profession at a glance. Make it
                  look premium, modern and successful, about ten years in
                  the future, with subtle African design influences and
                  warm lighting. Do not default to a generic corporate
                  office unless that is truly the workplace of this
                  profession.
                  """.formatted(occupation, occupation)
                : """
                  A luxurious, believable executive office about ten years
                  in the future: floor-to-ceiling glass windows, an
                  international city skyline, a premium executive desk,
                  dark wood, glass, brushed metal, subtle African luxury
                  design influences, warm architectural lighting.
                  """;

        String wardrobeRule = hasOccupation
                ? """
                  Dress the person in premium, tailored attire that fits
                  the profession "%s" at senior level (for example a
                  white coat for medicine, a tailored suit for law or
                  finance, a refined work uniform or smart-casual
                  professional wear for creative or technical fields).
                  Pick whatever is most realistic and respected for this
                  role. Elegant, natural styling, realistic fabrics.
                  """.formatted(occupation)
                : "Premium tailored executive clothing, refined accessories, "
                + "elegant styling, realistic materials.";

        String title = hasOccupation ? occupation : "CEO";

        // The person's details appear INSIDE the scene as a physical object.
        String nameplateRule = """
                Include ONE small, elegant engraved nameplate (a brass or
                dark-wood desk plaque, or another believable object that
                suits the profession, such as a refined office door or
                wall plaque) placed naturally in the scene, fully visible
                and in sharp focus, in the lower-middle of the image but
                above the bottom 25 percent of the picture.
                The plaque shows exactly two lines of text and nothing else:
                Line 1: "%s"
                Line 2: "%s"
                Spell both lines exactly as written, letter for letter,
                in clean, legible, classy lettering. Do not add a
                number, age, date, slogan or any other text.
                """.formatted(name, title);

        return """
            Create a photorealistic, premium editorial portrait of the
            person in the supplied reference photograph, imagining them
            about ten years in the future.

            SUBJECT DETAILS:
            Name: %s. Current age: %s. Portrayed age: about %s.
            Profession in ten years: %s.

            IDENTITY:
            Use the uploaded photograph as the primary identity reference.
            Preserve the person's recognizable face, facial structure,
            skin tone, ethnicity, eyes, nose, lips, jawline, hairline and
            overall likeness. Do not replace them with a generic model.
            Apply subtle, believable age progression to about %s years old
            (a few fine lines, mature confident look), never exaggerated.

            PROFESSION AND ENVIRONMENT:
            %s

            WARDROBE:
            %s

            NAMEPLATE (the only text allowed in the image):
            %s

            The result should communicate confidence, intelligence,
            achievement, authority in their field, and approachability.

            COMPOSITION AND HEADROOM (IMPORTANT):
            Medium-wide, camera-facing portrait, vertical 2:3.
            Leave generous empty headroom: the top of the head must sit
            about 15 to 20 percent below the top edge of the image, with
            clear background space above the head. Never crop or touch
            the top of the head, hair, or any headwear. Frame the person
            from about waist up, centred, with space on both sides.
            Keep the full head, face, shoulders, hands, the nameplate and
            the profession-related props visible. Keep every important
            element inside the top 75 percent of the image height, because
            the bottom edge may be trimmed.
            Natural anatomy and realistic hands.

            LIGHTING AND STYLE:
            Photorealistic luxury magazine photography, realistic skin
            texture, cinematic warm golden highlights, balanced contrast,
            believable reflections, high detail, shallow depth of field
            on the background, premium international editorial quality.

            STRICT EXCLUSIONS:
            Apart from the nameplate, no text, numbers, logo, watermark,
            border, caption, graphic overlay or frame. Never show the
            person's age anywhere. Do not make the image
            science-fictional. Generate only the portrait and its
            environment.

            The final image should answer: "Where could this person be
            ten years from now?"
            """.formatted(
                name,
                age,
                futureAge,
                career,
                futureAge,
                environmentRule,
                wardrobeRule,
                nameplateRule
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

            // Cover the photo window, centred horizontally and anchored to the
            // TOP so the headroom is kept (only the bottom gets cropped).
            double scale = Math.max((double) WIN_W / portrait.getWidth(),
                    (double) WIN_H / portrait.getHeight());
            int sw = (int) Math.ceil(portrait.getWidth() * scale);
            int sh = (int) Math.ceil(portrait.getHeight() * scale);
            int x = WIN_X + (WIN_W - sw) / 2;
            int y = WIN_Y;

            // Draw the frame first, then the portrait on top, clipped to the
            // photo window. This works even if the frame file's window is not
            // truly transparent (e.g. a checkerboard baked into the pixels).
            g.drawImage(frame, 0, 0, null);
            g.setClip(new RoundRectangle2D.Double(
                    WIN_X + 2, WIN_Y + 2, WIN_W - 4, WIN_H - 4, 24, 24));
            g.drawImage(portrait, x, y, sw, sh, null);
            g.setClip(null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", out)) {
            throw new IllegalStateException("Could not export the final portrait as PNG.");
        }
        return out.toByteArray();
    }

    /**
     * Trim, collapse whitespace, strip control characters (newlines etc.)
     * and cap the length, so user input cannot inject extra prompt lines.
     */
    private static String safeText(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String cleaned = value
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .replace("\"", "'")
                .trim();

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