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
 * Copyright 2014-2015 ForgeRock AS.
 */

package org.forgerock.authz.filter.http;

import org.forgerock.authz.filter.api.AuthorizationContext;
import org.forgerock.authz.filter.api.AuthorizationResult;
import org.forgerock.authz.filter.http.api.HttpAuthorizationModule;
import org.forgerock.services.context.Context;
import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Status;
import org.forgerock.util.AsyncFunction;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.Promises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the success result of the call to
 * {@link HttpAuthorizationModule#authorize(Context, Request, AuthorizationContext)}
 * asynchronously.
 *
 * @since 1.5.0
 */
class ResultHandler implements AsyncFunction<AuthorizationResult, Response, NeverThrowsException> {

    private final Logger logger = LoggerFactory.getLogger(ResultHandler.class);
    private final ResponseHandler responseHandler;
    private final Context context;
    private final Request request;
    private final Handler next;

    /**
     * Creates a new {@code SuccessHandler} instance.
     *
     * @param responseHandler A {@code ResultHandler} instance.
     * @param context The request {@code Context}.
     * @param request The {@code Request} instance.
     * @param next The downstream {@code Handler} instance.
     */
    ResultHandler(ResponseHandler responseHandler, Context context, Request request, Handler next) {
        this.responseHandler = responseHandler;
        this.context = context;
        this.request = request;
        this.next = next;
    }

    /**
     * <p>Asynchronously applies this function to the {@code result} parameter and returns a {@link Promise} for the
     * result.</p>
     *
     * <p>If the {@code AuthorizationResult} is successful, i.e. the request is authorized to access the requested
     * resource, the {@link Handler#handle(Context, Request)} is called to allow the request access to the resource.
     * If the {@code AuthorizationResult} is not successful, i.e. the request is not authorized to access the requested
     * resource, the {@code Response} will have a status of 403 set and the output of the reason for not
     * being authorized written to the response as JSON.</p>
     *
     * @param result The result of the authorization of the request.
     * @return The {@code Promise} representing the result of applying this function to {@code result}.
     */
    @Override
    public Promise<Response, NeverThrowsException> apply(AuthorizationResult result) {
        if (result.isAuthorized()) {
            logger.debug("Request authorized.");
            return next.handle(context, request);
        } else {
            logger.debug("Request unauthorized.");
            Response response = new Response(Status.FORBIDDEN);
            response.setEntity(responseHandler.getJsonForbiddenResponse(result.getReason(), result.getDetail())
                    .getObject());
            return Promises.newResultPromise(response);
        }
    }
}
