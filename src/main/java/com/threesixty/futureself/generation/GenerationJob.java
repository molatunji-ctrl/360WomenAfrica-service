
package com.threesixty.futureself.generation;

import java.time.Instant;
import java.util.UUID;

public class GenerationJob {

    private final String id;

    private volatile String status = "QUEUED";
    private volatile String message = "Your request is queued.";
    private volatile byte[] image;

    private final Instant createdAt = Instant.now();

    public GenerationJob() {
        this.id = UUID.randomUUID().toString();
    }

    public GenerationJob(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Generation ID cannot be empty."
            );
        }

        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public byte[] getImage() {
        return image;
    }

    /**
     * Marks the generation as processing.
     */
    public void processing() {
        this.image = null;
        this.status = "PROCESSING";
        this.message = "Creating your future-self portrait.";
    }

    /**
     * Stores the completed AI-generated portrait.
     * The image may include an AI-generated conference frame.
     */
    public void complete(byte[] image) {
        if (image == null || image.length == 0) {
            fail("The generated image is empty.");
            return;
        }

        this.image = image;
        this.status = "COMPLETED";
        this.message = "Your portrait is ready.";
    }

    /**
     * Marks the generation as failed.
     */
    public void fail(String message) {
        this.image = null;
        this.status = "FAILED";
        this.message = (message == null || message.isBlank())
                ? "Portrait generation failed. Please try again."
                : message;
    }

    /**
     * Deletes the generated image from this job.
     */
    public void delete() {
        this.image = null;
        this.status = "DELETED";
        this.message = "This generation has been deleted.";
    }
}
