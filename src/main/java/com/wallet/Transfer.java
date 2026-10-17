package com.wallet;

import java.util.Locale;

public class Transfer {
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final long id;
    private final long fromId;
    private final long toId;
    private final long amountPaise;
    private final String reference;
    private final String status;
    private final String reason;
    private final String createdAt;

    public Transfer(long id, long fromId, long toId, long amountPaise,
                    String reference, String status, String reason, String createdAt) {
        this.id = id;
        this.fromId = fromId;
        this.toId = toId;
        this.amountPaise = amountPaise;
        this.reference = reference;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public long getFromId() {
        return fromId;
    }

    public long getToId() {
        return toId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getReference() {
        return reference;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equalsIgnoreCase(status);
    }

    public String getFormattedAmount() {
        long rupees = amountPaise / 100;
        long remainingPaise = Math.abs(amountPaise % 100);
        return String.format(Locale.US, "₹%,d.%02d", rupees, remainingPaise);
    }

    @Override
    public String toString() {
        return "Transfer{" +
                "id=" + id +
                ", fromId=" + fromId +
                ", toId=" + toId +
                ", amount=" + getFormattedAmount() +
                ", reference='" + reference + '\'' +
                ", status='" + status + '\'' +
                ", reason='" + reason + '\'' +
                ", createdAt='" + createdAt + '\'' +
                '}';
    }
}
