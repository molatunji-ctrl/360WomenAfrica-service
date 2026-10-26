
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

    public synchronized void processing() {
        image = null;
        status = "PROCESSING";
        message = "Creating your future-self portrait.";
    }

    public synchronized void complete(byte[] image) {
        if (image == null || image.length == 0) {
            fail("The generated image is empty.");
            return;
        }

        this.image = image;
        status = "COMPLETED";
        message = "Your portrait is ready.";
    }

    public synchronized void fail(String message) {
        image = null;
        status = "FAILED";
        this.message = (message == null || message.isBlank())
                ? "Portrait generation failed. Please try again."
                : message;
    }

    public synchronized void delete() {
        image = null;
        status = "DELETED";
        message = "This generation has been deleted.";
    }
}
