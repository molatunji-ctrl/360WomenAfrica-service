package com.threesixty.futureself.generation;

import java.time.Instant;
import java.util.UUID;

public class GenerationJob {
    private final String id = UUID.randomUUID().toString();
    private volatile String status = "QUEUED";
    private volatile String message = "Your request is queued.";
    private volatile byte[] image;
    private final Instant createdAt = Instant.now();

    public String getId() { return id; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public byte[] getImage() { return image; }
    public void processing() { status = "PROCESSING"; message = "Creating your future-self portrait."; }
    public void complete(byte[] image) { this.image = image; status = "COMPLETED"; message = "Your portrait is ready."; }
    public void fail(String message) { this.status = "FAILED"; this.message = message; }
}
