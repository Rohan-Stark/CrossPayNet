package com.crosspaynet.events;

import com.crosspaynet.CrossPayNetApplication;
import com.crosspaynet.events.contracts.ConsumerExecutionResult;
import com.crosspaynet.events.contracts.EnvelopedEvent;
import com.crosspaynet.events.contracts.EventDeduplicator;
import com.crosspaynet.events.contracts.EventEnvelope;
import com.crosspaynet.events.contracts.EventValidationException;
import com.crosspaynet.events.contracts.TransactionalConsumerExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsModulithArchitectureTest {

    @Test
    void verifyEventsModuleArchitectureAndBoundaries() {
        ApplicationModules modules = ApplicationModules.of(CrossPayNetApplication.class);
        modules.verify();

        ApplicationModule eventsModule = modules.getModuleByName("events").orElseThrow();
        assertNotNull(eventsModule);

        NamedInterface contracts = eventsModule.getNamedInterfaces().getByName("contracts").orElseThrow();
        assertTrue(contracts.contains(EventEnvelope.class));
        assertTrue(contracts.contains(EnvelopedEvent.class));
        assertTrue(contracts.contains(EventDeduplicator.class));
        assertTrue(contracts.contains(TransactionalConsumerExecutor.class));
        assertTrue(contracts.contains(ConsumerExecutionResult.class));
        assertTrue(contracts.contains(EventValidationException.class));
    }
}
