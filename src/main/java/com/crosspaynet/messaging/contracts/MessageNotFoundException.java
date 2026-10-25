package com.crosspaynet.messaging.contracts;

/**
 * Thrown when a requested {@link MessageId} cannot be found.
 */
public class MessageNotFoundException extends MessagingException {

    private final MessageId messageId;

    public MessageNotFoundException(MessageId messageId) {
        super("PaymentMessage not found: " + messageId);
        this.messageId = messageId;
    }

    public MessageId getMessageId() {
        return messageId;
    }
}
