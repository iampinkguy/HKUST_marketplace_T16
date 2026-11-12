package com.hkust.marketplace.models;

import java.util.List;

public class ChatHistory {
    private String listing_id;
    private List<Message> messages;
    private boolean seen_buyer;
    private boolean seen_seller;

    public ChatHistory() {
    }

    public ChatHistory(String listing_id, List<Message> messages, boolean seen_buyer, boolean seen_seller) {
        this.listing_id = listing_id;
        this.messages = messages;
        this.seen_buyer = seen_buyer;
        this.seen_seller = seen_seller;
    }

    public String getListing_id() {
        return listing_id;
    }

    public void setListing_id(String listing_id) {
        this.listing_id = listing_id;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public void appendMessage(Message message) {
        this.messages.add(message);
    }

    public boolean isSeen_buyer() {
        return seen_buyer;
    }

    public void setSeen_buyer(boolean seen_buyer) {
        this.seen_buyer = seen_buyer;
    }

    public boolean isSeen_seller() {
        return seen_seller;
    }

    public void setSeen_seller(boolean seen_seller) {
        this.seen_seller = seen_seller;
    }
}
