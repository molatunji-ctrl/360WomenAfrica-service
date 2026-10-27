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
        String participantName = name == null || name.isBlank()
                ? "the participant"
                : name.trim();

        String participantAge = age == null || age.isBlank()
                ? "not specified"
                : age.trim();

        String career = occupation == null || occupation.isBlank()
                ? "a successful professional pursuing a meaningful career"
                : occupation.trim();

        return """
            Create an exceptionally detailed, photorealistic FUTURE SELF
            portrait of the exact person in the supplied reference photograph,
            imagining their life and professional achievements approximately
            ten years in the future.

            THE PRIMARY OBJECTIVE:
            Create a believable, aspirational, personalized vision of who this
            particular person could become. The final result must reflect the
            participant's actual occupation and career aspirations, not a
            generic corporate executive template.

            1. IDENTITY AND FACIAL LIKENESS

            Use the uploaded photograph as the primary and authoritative
            reference for the person's identity.

            Preserve their recognizable facial structure, face shape, eyes,
            nose, lips, jawline, skin tone, complexion, ethnicity and distinctive
            facial characteristics.

            Maintain a strong resemblance to the original person. Do not replace
            their face with a generic attractive model or change their identity.

            Apply subtle, realistic age progression of approximately ten years.
            The person should look like a believable older version of themselves,
            not an entirely different person.

            Keep skin texture realistic, with natural pores and believable
            facial details. Avoid excessive retouching, artificial skin,
            exaggerated aging and unrealistic beauty filters.

            2. OCCUPATION-SPECIFIC FUTURE

            The participant's stated occupation is: %s.

            Build the entire professional concept around this occupation.

            Understand what this occupation involves, the professional
            environment in which it operates, the clothing appropriate to it,
            the tools or equipment associated with it, and the responsibilities
            an experienced professional in this field might have.

            Choose a believable future-career scenario that demonstrates
            significant progress, expertise, confidence and achievement
            within the stated occupation.

            The occupation must determine the following:

            - WARDROBE: Appropriate professional clothing, uniforms, protective
              equipment or specialist attire when relevant to the occupation.
            - ENVIRONMENT: A realistic workplace, studio, clinic, laboratory,
              office, workshop, construction site, courtroom, classroom,
              creative space or other setting that genuinely fits the career.
            - PROPS AND TOOLS: Relevant professional equipment, instruments,
              documents, technology or materials, used naturally and correctly.
            - BODY LANGUAGE: A believable professional posture and activity
              consistent with the person's role and level of expertise.
            - CAREER ACHIEVEMENT: Subtle environmental details that suggest
              experience, responsibility, advancement and future success.

            Examples of occupation-specific interpretation:

            A DOCTOR may be portrayed in a premium modern medical environment,
            wearing appropriate clinical attire and surrounded by realistic
            medical equipment.

            An ARCHITECT may be portrayed in a sophisticated design studio,
            with architectural models, drawings, plans and design technology.

            A SOFTWARE ENGINEER may be portrayed in a credible technology
            workspace, with development tools and engineering equipment relevant
            to their work.

            AN ENTREPRENEUR may be portrayed leading a credible business,
            presenting an idea, reviewing operations or working in a space
            appropriate to the business they have built.

            A LAWYER may be portrayed in a distinguished legal environment,
            with appropriate professional attire and realistic legal materials.

            A TEACHER may be portrayed in an inspiring, well-equipped educational
            environment, demonstrating professional confidence and expertise.

            These examples are illustrative, not mandatory templates.
            Always prioritize the actual occupation supplied by the participant.

            Do not portray every participant as a CEO, business owner or
            corporate executive. Do not place a person in a medical, legal,
            technical or other specialist role unless that role fits their
            stated occupation.

            If the occupation is broad or ambiguous, select a reasonable,
            respectful and believable interpretation without inventing
            specific qualifications, credentials or achievements.

            3. PERSONALIZED VISUAL STORYTELLING

            Every visual element should reinforce the participant's own
            future-career story.

            Make the portrait feel intentionally created for this person,
            rather than generated from a reusable stock-photo template.

            Use the occupation to guide the setting, clothing, professional
            equipment, lighting, composition and background details.

            Include several meaningful environmental details that communicate
            what this professional does and what an accomplished future in
            that field might look like.

            Make the environment detailed and interesting without allowing
            background objects to distract from the person's face.

            Show success through professional competence, quality surroundings,
            thoughtful design, confidence and realistic accomplishments.
            Avoid excessive luxury, unrelated status symbols or exaggerated
            displays of wealth.

            Do not invent named employers, real company affiliations, awards,
            academic degrees, professional licences or specific achievements
            that the participant did not provide.

            4. WARDROBE AND PROFESSIONAL AUTHENTICITY

            Select clothing that is appropriate to the occupation and the
            imagined level of experience.

            Prioritize realistic fabrics, accurate garment construction,
            natural folds, appropriate fit and sophisticated styling.

            Use premium styling where appropriate, without forcing a suit,
            tie or formal business outfit onto every occupation.

            Respect the person's existing identity and appearance. Avoid
            unnecessary changes to their ethnicity, skin tone or distinctive
            features.

            5. ENVIRONMENT AND BACKGROUND DETAIL

            Create a visually rich, believable professional environment
            appropriate to the specified occupation.

            Include layered background detail, realistic architectural or
            workplace features, relevant equipment, natural material textures,
            realistic reflections and subtle environmental depth.

            Use sophisticated composition and carefully balanced lighting.

            Every important background object must make sense in the context
            of the person's actual profession.

            The environment should communicate a clear story at first glance,
            even when viewed as a small portrait on a phone screen.

            Avoid random decorative objects, unrelated equipment, confusing
            background elements and futuristic science-fiction environments.

            6. CRITICAL COMPOSITION: GENEROUS HEADROOM

            This is a vertical portrait designed to be placed inside a
            predefined portrait frame.

            Leave generous, clearly visible negative space between the top
            edge of the image and the highest point of the person's head.

            Aim for approximately 15 to 20 percent of the image height as
            headroom, measured from the top edge to the top of the hair or head.

            Keep the entire head, hairstyle, hairline and forehead comfortably
            inside the image, with visible background above the head.

            NEVER crop the top of the head, hair, forehead or hairstyle.
            NEVER position the head so close to the top edge that the portrait
            feels cramped.

            Place the person's head in the upper-middle portion of the image,
            leaving breathing room above it while maintaining a balanced
            portrait composition.

            Frame the person from approximately the waist or mid-torso upward
            whenever appropriate, allowing the shoulders, upper body and
            occupation-relevant clothing to remain visible.

            Keep the face prominent and recognizable without making it
            excessively large in the frame.

            Show enough of the surrounding professional environment to tell
            the person's career story.

            Keep the important facial features, head, shoulders and upper body
            within a central safe area. Allow reasonable space around the
            shoulders and sides of the body.

            Compose the image with the expectation that it will later be
            cropped to fit a slightly wider vertical photo window. Keep the
            head and essential facial features safely away from the top,
            bottom and side edges.

            Prioritize a complete, comfortably framed head over an excessively
            tight close-up.

            7. POSE, ANATOMY AND EXPRESSION

            Use a confident, natural and approachable expression appropriate
            to the occupation.

            The person may be standing, seated or naturally engaged in a
            professional activity, depending on the career scenario.

            Maintain realistic anatomy, natural shoulder proportions, correct
            hands and fingers, believable posture and physically plausible
            interaction with any professional equipment.

            Avoid awkward poses, distorted hands, extra fingers, duplicated
            body parts and unnatural facial expressions.

            8. LIGHTING AND PHOTOGRAPHIC QUALITY

            Use premium international editorial portrait photography.

            Create realistic, flattering, professionally controlled lighting
            that reveals natural skin texture and preserves the person's likeness.

            Use balanced highlights and shadows, natural depth of field,
            realistic reflections and carefully controlled color grading.

            Adapt the lighting to the environment. A clinical workspace,
            creative studio, outdoor professional setting and executive office
            should not all look identical.

            Produce a sophisticated, cinematic yet believable photograph.
            The result must look like a real professional portrait captured
            by an experienced photographer, not a painting, illustration,
            3D render or artificial fantasy image.

            9. STRICT EXCLUSIONS

            Do not add captions, generated text, a participant name, logos,
            watermarks, borders, graphic overlays, decorative frames or
            artificial branding.

            Do not place readable fabricated text on documents, equipment,
            screens, certificates or nameplates.

            Do not substitute a generic CEO office for an occupation-specific
            environment.

            Do not sacrifice facial likeness, headroom or professional accuracy
            for dramatic styling.

            Generate only the portrait and its occupation-appropriate setting.
            The application will add its own separate branded frame afterward.

            10. PARTICIPANT CONTEXT

            Participant name: %s.
            Current age supplied by participant: %s.
            Stated occupation: %s.
            Imagined future horizon: approximately ten years.

            Use the name and age as contextual guidance only. Do not render
            the participant's name or age as visible text in the image.

            FINAL QUALITY STANDARD:

            The result should make the participant recognize themselves,
            understand their future professional identity from the visual
            details, and feel that the portrait was specifically created
            around their occupation and potential.

            Above all, preserve identity, leave generous headroom, create
            occupation-specific detail and produce a believable, inspiring
            future-self portrait.
            """.formatted(
                career,
                participantName,
                participantAge,
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

            // Draw the frame first, then the portrait on top, clipped to the
            // photo window. This works even if the frame file's window is not
            // truly transparent (e.g. a checkerboard baked into the pixels).
            g.drawImage(frame, 0, 0, null);
            g.setClip(new java.awt.geom.RoundRectangle2D.Double(
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