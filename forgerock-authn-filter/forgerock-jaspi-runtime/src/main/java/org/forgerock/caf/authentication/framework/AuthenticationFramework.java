/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2015 ForgeRock AS.
 */

package org.forgerock.caf.authentication.framework;

import static org.forgerock.caf.authentication.framework.AuditTrail.AUDIT_TRAIL_KEY;
import static org.forgerock.caf.authentication.framework.AuthContexts.*;
import static org.forgerock.caf.authentication.framework.AuthStatusUtils.isSendFailure;
import static org.forgerock.caf.authentication.framework.AuthStatusUtils.isSuccess;
import static org.forgerock.json.fluent.JsonValue.*;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthStatus;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.forgerock.caf.authentication.api.AsyncServerAuthContext;
import org.forgerock.caf.authentication.api.AuthenticationException;
import org.forgerock.caf.authentication.api.MessageContext;
import org.forgerock.http.Context;
import org.forgerock.http.Handler;
import org.forgerock.http.HttpContext;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.ResponseException;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.util.Reject;
import org.forgerock.util.promise.AsyncFunction;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.Promises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An authentication framework for protecting all types of resources.</p>
 *
 * <p>The authentication framework can be configured with a single session authentication module,
 * which will authenticate requests based on some session identifier, and an ordered list of
 * authentication modules, that are executed in order on a first succeeds wins basis.</p>
 *
 * <p>The authentication framework must be configured with a non-{@code null} {@link AuditApi}
 * instance, so that it can audit authentication outcomes.</p>
 *
 * @since 2.0.0
 */
public final class AuthenticationFramework {

    /**
     * Runtime slf4j debug logger.
     */
    public static final Logger LOG = LoggerFactory.getLogger(AuthenticationFramework.class);

    /**
     * Indicates that the request could not be authenticated either because no credentials were
     * provided or the credentials provided did not pass authentication. Equivalent to HTTP
     * status: 401 Unauthorized.
     */
    public static final int UNAUTHORIZED_HTTP_ERROR_CODE = 401;

    /**
     * The exception message for a 401 ResourceException.
     */
    public static final String UNAUTHORIZED_ERROR_MESSAGE = "Access Denied";

    /**
     * The name of the HTTP Servlet Request attribute where the principal name of the user/client
     * making the request will be set.
     */
    public static final String ATTRIBUTE_AUTH_PRINCIPAL = "org.forgerock.authentication.principal";

    /**
     * The name of the HTTP Servlet Request attribute where any additional authentication context
     * information will be set. It MUST contain a {@code Map} if present.
     */
    public static final String ATTRIBUTE_AUTH_CONTEXT = "org.forgerock.authentication.context";

    /**
     * The name of the HTTP Servlet Request attribute where the unique id of the request will be
     * set.
     */
    public static final String ATTRIBUTE_REQUEST_ID = "org.forgerock.authentication.request.id";

    @SuppressWarnings("unchecked")
    static final Collection<Class<?>> REQUIRED_MESSAGE_TYPES_SUPPORT =
            new HashSet<Class<?>>(Arrays.asList(Request.class, Response.class));

    private final Logger logger;
    private final AuditApi auditApi;
    private final FailureResponseHandler responseHandler;
    private final AsyncServerAuthContext authContext;
    private final Subject serviceSubject;
    private final Promise<List<Void>, AuthenticationException> initializationPromise;

    /**
     * Creates a new {@code JaspiRuntime} instance that will use the configured {@code authContext}
     * to authenticate incoming request and secure outgoing response messages.
     *
     * @param logger The non-{@code null} {@link Logger} instance.
     * @param auditApi The non-{@code null} {@link AuditApi} instance.
     * @param responseHandler The non-{@code null} {@link FailureResponseHandler} instance.
     * @param authContext The non-{@code null} {@link AsyncServerAuthContext} instance.
     * @param serviceSubject The non-{@code null} service {@link Subject}.
     * @param initializationPromise A {@link Promise} which will be completed once the configured
     *                              auth modules have been initialised.
     */
    AuthenticationFramework(Logger logger, AuditApi auditApi, FailureResponseHandler responseHandler,
            AsyncServerAuthContext authContext, Subject serviceSubject,
            Promise<List<Void>, AuthenticationException> initializationPromise) {
        Reject.ifNull(logger, auditApi, responseHandler, authContext, serviceSubject, initializationPromise);
        this.logger = logger;
        this.auditApi = auditApi;
        this.responseHandler = responseHandler;
        this.authContext = withValidation(withAuditing(withLogging(logger, authContext)));
        this.serviceSubject = serviceSubject;
        this.initializationPromise = initializationPromise;
    }

    /**
     * Authenticates incoming request messages and if successful calls the downstream filter or
     * handler and then secures the returned response.
     *
     * @param context The request context.
     * @param request The request.
     * @param next The downstream filter or handler in the chain that should only be called if the
     *             request was successfully authenticated.
     * @return A {@code Promise} representing the response to be returned to the client.
     */
    Promise<Response, ResponseException> processMessage(Context context, Request request, final Handler next) {

        final Subject clientSubject = new Subject();
        Map<String, Object> contextMap = new HashMap<String, Object>();
        AuditTrail auditTrail = new AuditTrail(auditApi, contextMap);
        final MessageContextImpl messageContext = new MessageContextImpl(context, request, auditTrail);

        //TODO these need to be gone...
        messageContext.getRequestContextMap().put(AuthenticationFramework.ATTRIBUTE_AUTH_CONTEXT, contextMap);
        messageContext.getRequestContextMap().put(AUDIT_TRAIL_KEY, auditTrail);

        return initializationPromise
                .thenAsync(onInitializationSuccess(messageContext, clientSubject, next), onFailure(messageContext));
    }

    private AsyncFunction<List<Void>, Response, ResponseException> onInitializationSuccess(
            final MessageContext context, final Subject clientSubject, final Handler next) {
        return new AsyncFunction<List<Void>, Response, ResponseException>() {
            @Override
            public Promise<Response, ResponseException> apply(List<Void> voids) {
                return validateRequest(context, clientSubject, next)
                        .thenAlways(new Runnable() {
                            @Override
                            public void run() {
                                authContext.cleanSubject(context, clientSubject);
                            }
                        });
            }
        };
    }

    private Promise<Response, ResponseException> validateRequest(final MessageContext context, Subject clientSubject,
            final Handler next) {
        return authContext.validateRequest(context, clientSubject, serviceSubject)
                .thenAsync(processResult(context, clientSubject))
                .thenAsync(onValidateRequestSuccess(context, next), onFailure(context));
    }

    private AsyncFunction<AuthStatus, AuthStatus, AuthenticationException> processResult(final MessageContext context,
            final Subject clientSubject) {
        return new AsyncFunction<AuthStatus, AuthStatus, AuthenticationException>() {
            @Override
            public Promise<AuthStatus, AuthenticationException> apply(AuthStatus authStatus) {
                if (isSendFailure(authStatus)) {
                    context.getResponse().setStatusAndReason(401);
                    logger.debug("Authentication has failed.");
                    ResourceException jre = ResourceException.getException(UNAUTHORIZED_HTTP_ERROR_CODE,
                            UNAUTHORIZED_ERROR_MESSAGE);
                    return Promises.newFailedPromise(new AuthenticationException(jre));
                } else if (isSuccess(authStatus)) {
                    String principalName = null;
                    for (Principal principal : clientSubject.getPrincipals()) {
                        if (principal.getName() != null) {
                            principalName = principal.getName();
                            break;
                        }
                    }

                    Map<String, Object> contextMap =
                            getMap(context.getRequestContextMap(), AuthenticationFramework.ATTRIBUTE_AUTH_CONTEXT);
                    logger.debug("Setting principal name, {}, and {} context values on to the request.",
                            principalName, contextMap.size());
                    Map<String, Object> requestAttributes = context.asContext(HttpContext.class).getAttributes();
                    requestAttributes.put(AuthenticationFramework.ATTRIBUTE_AUTH_PRINCIPAL, principalName);
                    requestAttributes.put(AuthenticationFramework.ATTRIBUTE_AUTH_CONTEXT, contextMap);
                }
                return Promises.newSuccessfulPromise(authStatus);
            }
        };
    }

    private AsyncFunction<AuthStatus, Response, ResponseException> onValidateRequestSuccess(
            final MessageContext context, final Handler next) {
        return new AsyncFunction<AuthStatus, Response, ResponseException>() {
            @Override
            public Promise<Response, ResponseException> apply(AuthStatus authStatus) {
                if (isSuccess(authStatus)) {
                    AuditTrail auditTrail = context.getAuditTrail();
                    context.asContext(HttpContext.class).getAttributes()
                            .put(AuthenticationFramework.ATTRIBUTE_REQUEST_ID, auditTrail.getRequestId());
                    return grantAccess(context, next);
                }
                return Promises.newSuccessfulPromise(context.getResponse());
            }
        };
    }

    private Promise<Response, ResponseException> grantAccess(final MessageContext context, Handler next) {
        return next.handle(context, context.getRequest())
                .thenAsync(new AsyncFunction<Response, Response, ResponseException>() {
                    @Override
                    public Promise<Response, ResponseException> apply(Response response) {
                        context.setResponse(response);
                        return secureResponse(context);
                    }
                }, new AsyncFunction<ResponseException, Response, ResponseException>() {
                    @Override
                    public Promise<Response, ResponseException> apply(ResponseException error) {
                        context.setResponse(error.getResponse());
                        return secureResponse(context);
                    }
                });
    }

    private Promise<Response, ResponseException> secureResponse(final MessageContext context) {
        return authContext.secureResponse(context, serviceSubject)
                .thenAsync(onSecureResponseSuccess(context), onFailure(context));
    }

    private AsyncFunction<AuthStatus, Response, ResponseException> onSecureResponseSuccess(
            final MessageContext context) {
        return new AsyncFunction<AuthStatus, Response, ResponseException>() {
            @Override
            public Promise<Response, ResponseException> apply(AuthStatus authStatus) {
                if (isSendFailure(authStatus)) {
                    context.getResponse().setStatusAndReason(500);
                }
                return Promises.newSuccessfulPromise(context.getResponse());
            }
        };
    }

    private AsyncFunction<AuthenticationException, Response, ResponseException> onFailure(
            final MessageContext context) {
        return new AsyncFunction<AuthenticationException, Response, ResponseException>() {
            @Override
            public Promise<Response, ResponseException> apply(AuthenticationException error) {
                ResourceException jre;
                if (error.getCause() instanceof ResourceException) {
                    jre = (ResourceException) error.getCause();
                } else {
                    jre = ResourceException.getException(ResourceException.INTERNAL_ERROR, error.getMessage());
                }
                AuditTrail auditTrail = context.getAuditTrail();
                List<Map<String, Object>> failureReasonList = auditTrail.getFailureReasons();
                if (failureReasonList != null && !failureReasonList.isEmpty()) {
                    jre.setDetail(json(object(field("failureReasons", failureReasonList))));
                }
                responseHandler.handle(jre, context);
                return Promises.newFailedPromise(new ResponseException(context.getResponse(),
                        error.getMessage(), error));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> containingMap, String key) {
        Map<String, Object> map = (Map<String, Object>) containingMap.get(key);
        if (map == null) {
            map = new HashMap<String, Object>();
            containingMap.put(key, map);
        }
        return map;
    }

    @Override
    public String toString() {
        return "AuthContext: " + authContext.toString() + ", Response Handlers: " + responseHandler.toString();
    }
}