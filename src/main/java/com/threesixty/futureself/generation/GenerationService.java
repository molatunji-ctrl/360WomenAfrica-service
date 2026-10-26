
package com.threesixty.futureself.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GenerationService {

    private static final Logger log =
            LoggerFactory.getLogger(GenerationService.class);

    private static final String GENERIC_FAILURE_MESSAGE =
            "Image generation failed. Check the backend logs and OpenAI model access, then try again.";

    private final Map<String, GenerationJob> jobs =
            new ConcurrentHashMap<>();

    private final ExecutorService executor =
            Executors.newFixedThreadPool(3);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public GenerationService(
            @Value("${app.openai.api-key:}") String apiKey,
            @Value("${app.openai.model:gpt-image-1}") String model,
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
            int age,
            String occupation,
            boolean consent) {

        if (!consent) {
            throw new IllegalArgumentException(
                    "Consent is required to process the photo.");
        }

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException(
                    "Please provide a photo.");
        }

        if (image.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "Image must be 10 MB or smaller.");
        }

        if (age < 18 || age > 110) {
            throw new IllegalArgumentException(
                    "Participants must be 18 or older.");
        }

        if (occupation == null
                || occupation.isBlank()
                || occupation.length() > 120) {
            throw new IllegalArgumentException(
                    "Please provide a valid occupation.");
        }

        if (name == null
                || name.isBlank()
                || name.length() > 100) {
            throw new IllegalArgumentException(
                    "Please provide a valid name.");
        }

        String contentType = image.getContentType();

        if (contentType == null
                || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException(
                    "Upload must be an image.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "AI generation is not configured. "
                            + "Set OPENAI_API_KEY on the backend.");
        }

        GenerationJob job = new GenerationJob();
        jobs.put(job.getId(), job);

        try {
            byte[] source = image.getBytes();
            String prompt = buildPrompt(age, occupation);

            executor.execute(() ->
                    runGeneration(job, source, contentType, prompt));

        } catch (IOException ex) {
            jobs.remove(job.getId());

            throw new IllegalArgumentException(
                    "Could not read the uploaded image.", ex);

        } catch (RuntimeException ex) {
            jobs.remove(job.getId());
            throw ex;
        }

        return job;
    }

    public GenerationJob find(String id) {
        return jobs.get(id);
    }

    public void delete(String id) {
        jobs.remove(id);
    }

    private String buildPrompt(int age, String occupation) {
        String safeOccupation =
                occupation.replaceAll("[\\r\\n]", " ");

        return "Edit the supplied reference photograph into a "
                + "photorealistic, respectful aspirational visualisation "
                + "of the same adult person approximately ten years older. "
                + "Preserve their recognizable facial identity, natural "
                + "skin tone, ethnicity, and distinctive features as "
                + "closely as possible. The person is a "
                + safeOccupation
                + "; reflect this occupation through tasteful, plausible "
                + "professional styling, clothing, tools or environment "
                + "without using stereotypes. Create a confident, natural "
                + "pose, realistic skin texture, excellent photographic "
                + "detail, and elegant editorial lighting. Premium "
                + "contemporary African leadership campaign aesthetic, "
                + "warm golden highlights and subtle deep-red/black "
                + "accents. Keep the person's face the focus. No text, "
                + "logos, watermarks, collage, or extra people. Do not "
                + "radically change facial structure. This is an artistic "
                + "possibility, not a prediction. The person's current "
                + "stated age is "
                + age + ".";
    }

    private void runGeneration(
            GenerationJob job,
            byte[] source,
            String contentType,
            String prompt) {

        job.processing();

        try {
            String filename = getFilename(contentType);

            ByteArrayResource imageResource =
                    new ByteArrayResource(source) {
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

            MultiValueMap<String, Object> form =
                    new LinkedMultiValueMap<>();

            form.add("model", model);
            form.add("prompt", prompt);
            form.add("size", "1024x1024");
            form.add("image", imageEntity);

            log.info(
                    "Starting image generation. Job ID: {}, model: {}, "
                            + "image type: {}, image size: {} bytes",
                    job.getId(), model, contentType, source.length);

            String response = restClient.post()
                    .uri("/images/edits")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "OpenAI returned an empty response.");
            }

            JsonNode root = mapper.readTree(response);
            JsonNode data = root.path("data");

            if (!data.isArray()
                    || data.isEmpty()
                    || !data.get(0).hasNonNull("b64_json")
                    || data.get(0).path("b64_json").asText().isBlank()) {
                throw new IllegalStateException(
                        "OpenAI response did not contain image data.");
            }

            byte[] output = Base64.getDecoder().decode(
                    data.get(0).path("b64_json").asText());

            if (output.length == 0) {
                throw new IllegalStateException(
                        "OpenAI returned an empty image.");
            }

            job.complete(output);

            log.info(
                    "Image generation completed. Job ID: {}, "
                            + "output size: {} bytes",
                    job.getId(), output.length);

        } catch (RestClientResponseException ex) {
            // Do not log request headers or the API key.
            // OpenAI's response body can contain diagnostic details.
            log.error(
                    "OpenAI API request failed. Job ID: {}, "
                            + "HTTP status: {}, response: {}",
                    job.getId(),
                    ex.getStatusCode().value(),
                    sanitizeError(ex.getResponseBodyAsString()));

            job.fail(GENERIC_FAILURE_MESSAGE);

        } catch (Exception ex) {
            log.error(
                    "Image generation failed unexpectedly. Job ID: {}, "
                            + "error type: {}",
                    job.getId(),
                    ex.getClass().getSimpleName(),
                    ex);

            job.fail(GENERIC_FAILURE_MESSAGE);
        }
    }

    private String getFilename(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "reference.png";
            case "image/webp" -> "reference.webp";
            case "image/jpeg", "image/jpg" -> "reference.jpg";
            default -> throw new IllegalArgumentException(
                    "Unsupported image type: " + contentType);
        };
    }

    private String sanitizeError(String response) {
        if (response == null || response.isBlank()) {
            return "(empty error response)";
        }

        // Limit log size and redact any accidental bearer-key text.
        String sanitized = response
                .replaceAll(
                        "(?i)Bearer\\s+\\S+",
                        "Bearer [REDACTED]")
                .replaceAll(
                        "(?i)(sk-[A-Za-z0-9_-]{8,})",
                        "[REDACTED]");

        return sanitized.length() > 4000
                ? sanitized.substring(0, 4000) + "... [truncated]"
                : sanitized;
    }
}
