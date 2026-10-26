CREATE TABLE IF NOT EXISTS system.event_inbox (
    message_id UUID NOT NULL,
    consumer_group TEXT NOT NULL,
    event_type TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (message_id, consumer_group)
);

CREATE INDEX IF NOT EXISTS event_inbox_processed_at_idx 
    ON system.event_inbox (processed_at);

CREATE INDEX IF NOT EXISTS event_inbox_event_type_idx 
    ON system.event_inbox (event_type);

CREATE TABLE IF NOT EXISTS system.sample_consumer_effect (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    consumer_group TEXT NOT NULL,
    event_type TEXT NOT NULL,
    effect_payload TEXT NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sample_consumer_effect UNIQUE (event_id, consumer_group)
);
