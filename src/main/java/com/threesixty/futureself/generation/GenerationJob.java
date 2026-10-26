package com.threesixty.futureself.generation;

import java.time.Instant;

public class GenerationJob {

    private final String id;
    private final Instant createdAt;

    private volatile String status = "QUEUED";
    private volatile String message = "Your request is queued.";
    private volatile byte[] image;

    public GenerationJob(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Generation ID cannot be empty."
            );
        }

        this.id = id;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public byte[] getImage() {
        return image;
    }

    public synchronized void processing() {
        if ("DELETED".equals(status)) {
            return;
        }

        image = null;
        status = "PROCESSING";
        message = "Creating your future-self portrait.";
    }

    public synchronized void complete(byte[] generatedImage) {
        if ("DELETED".equals(status)) {
            return;
        }

        if (generatedImage == null || generatedImage.length == 0) {
            fail("The generated portrait was empty. Please try again.");
            return;
        }

        image = generatedImage;
        status = "COMPLETED";
        message = "Your portrait is ready.";
    }

    public synchronized void fail(String failureMessage) {
        if ("DELETED".equals(status)) {
            return;
        }

        image = null;
        status = "FAILED";
        message = failureMessage == null || failureMessage.isBlank()
                ? "We could not generate your portrait. Please try again."
                : failureMessage;
    }

    public synchronized void delete() {
        image = null;
        status = "DELETED";
        message = "This generation has been deleted.";
    }
}