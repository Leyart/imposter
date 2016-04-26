package com.gatehill.imposter.plugin;

import com.gatehill.imposter.model.ResponseBehaviour;
import com.gatehill.imposter.model.ResponseBehaviourType;
import com.gatehill.imposter.plugin.config.ResourceConfig;
import com.gatehill.imposter.service.ResponseService;
import com.gatehill.imposter.util.InjectorUtil;
import io.vertx.ext.web.RoutingContext;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public interface ScriptedPlugin<C extends ResourceConfig> {
    default void scriptHandler(C config, RoutingContext routingContext, Consumer<ResponseBehaviour> defaultBehaviourHandler) {
        scriptHandler(config, routingContext, null, defaultBehaviourHandler);
    }

    default void scriptHandler(C config, RoutingContext routingContext, Map<String, Object> additionalContext,
                               Consumer<ResponseBehaviour> defaultBehaviourHandler) {

        final ResponseService responseService = InjectorUtil.getInjector().getInstance(ResponseService.class);

        try {
            final ResponseBehaviour responseBehaviour = responseService.getResponseBehaviour(
                    routingContext, config, additionalContext, Collections.emptyMap());

            switch (responseBehaviour.getBehaviourType()) {
                case IMMEDIATE_RESPONSE:
                    routingContext.response()
                            .setStatusCode(responseBehaviour.getStatusCode())
                            .end();
                    break;

                default:
                    // default behaviour
                    defaultBehaviourHandler.accept(responseBehaviour);
                    break;
            }

        } catch (Exception e) {
            routingContext.fail(e);
        }
    }
}
