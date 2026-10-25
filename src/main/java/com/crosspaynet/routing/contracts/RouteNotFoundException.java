package com.crosspaynet.routing.contracts;

/**
 * Thrown when a requested correspondent route cannot be found by its reference or identifier.
 */
public class RouteNotFoundException extends RoutingException {

    public RouteNotFoundException(String routeReference) {
        super("CorrespondentRoute not found for routeReference: " + routeReference);
    }

    public RouteNotFoundException(RouteId routeId) {
        super("CorrespondentRoute not found for routeId: " + routeId);
    }
}
