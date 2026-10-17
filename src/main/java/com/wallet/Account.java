package com.wallet;

import java.util.Locale;

public class Account {
    private final long id;
    private final String ownerName;
    private final long balancePaise;
    private final String createdAt;

    public Account(long id, String ownerName, long balancePaise, String createdAt) {
        this.id = id;
        this.ownerName = ownerName;
        this.balancePaise = balancePaise;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getFormattedBalance() {
        return formatPaise(balancePaise);
    }

    public static String formatPaise(long paise) {
        long rupees = paise / 100;
        long remainingPaise = Math.abs(paise % 100);
        return String.format(Locale.US, "₹%,d.%02d", rupees, remainingPaise);
    }

    @Override
    public String toString() {
        return "Account{" +
                "id=" + id +
                ", ownerName='" + ownerName + '\'' +
                ", balance=" + getFormattedBalance() +
                ", createdAt='" + createdAt + '\'' +
                '}';
    }
}
