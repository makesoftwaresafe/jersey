/*
 * Copyright (c) 2011, 2021 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.client;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.RedirectionException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.CompletionStageRxInvoker;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.ResponseProcessingException;
import javax.ws.rs.client.RxInvoker;
import javax.ws.rs.client.RxInvokerProvider;
import javax.ws.rs.client.SyncInvoker;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.internal.ClientResponseProcessingException;
import org.glassfish.jersey.client.internal.LocalizationMessages;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.internal.inject.Bindings;
import org.glassfish.jersey.internal.inject.DisposableSupplier;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.internal.util.Producer;
import org.glassfish.jersey.internal.util.PropertiesHelper;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.process.internal.ExecutorProviders;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.spi.ExecutorServiceProvider;

/**
 * Jersey implementation of {@link javax.ws.rs.client.Invocation JAX-RS client-side
 * request invocation} contract.
 *
 * @author Marek Potociar
 */
public class JerseyInvocation implements javax.ws.rs.client.Invocation {

    private static final Logger LOGGER = Logger.getLogger(JerseyInvocation.class.getName());

    private final ClientRequest requestContext;
    // Copy request context when invoke or submit methods are invoked.
    private final boolean copyRequestContext;

    private boolean ignoreResponseException;

    private JerseyInvocation(final Builder builder) {
        this(builder, false);
    }

    private JerseyInvocation(final Builder builder, final boolean copyRequestContext) {
        validateHttpMethodAndEntity(builder.requestContext);

        this.requestContext = new ClientRequest(builder.requestContext);
        this.copyRequestContext = copyRequestContext;

        Object value = builder.requestContext.getConfiguration()
                .getProperty(ClientProperties.IGNORE_EXCEPTION_RESPONSE);
        if (value != null) {
            Boolean booleanValue = PropertiesHelper.convertValue(value, Boolean.class);
            if (booleanValue != null) {
                this.ignoreResponseException = booleanValue;
            }
        }
    }

    private enum EntityPresence {
        MUST_BE_NULL,
        MUST_BE_PRESENT,
        OPTIONAL
    }

    private static final Map<String, EntityPresence> METHODS = initializeMap();

    private static Map<String, EntityPresence> initializeMap() {
        final Map<String, EntityPresence> map = new HashMap<>();

        map.put("DELETE", EntityPresence.MUST_BE_NULL);
        map.put("GET", EntityPresence.MUST_BE_NULL);
        map.put("HEAD", EntityPresence.MUST_BE_NULL);
        map.put("OPTIONS", EntityPresence.OPTIONAL);
        map.put("PATCH", EntityPresence.MUST_BE_PRESENT);
        map.put("POST", EntityPresence.OPTIONAL); // we allow to post null instead of entity
        map.put("PUT", EntityPresence.MUST_BE_PRESENT);
        map.put("TRACE", EntityPresence.MUST_BE_NULL);
        return map;
    }

    private void validateHttpMethodAndEntity(final ClientRequest request) {
        boolean suppressExceptions;
        suppressExceptions = PropertiesHelper.isProperty(
                request.getConfiguration().getProperty(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION));

        final Object shcvProperty = request.getProperty(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION);
        if (shcvProperty != null) { // override global configuration with request-specific
            suppressExceptions = PropertiesHelper.isProperty(shcvProperty);
        }

        final String method = request.getMethod();

        final EntityPresence entityPresence = METHODS.get(method.toUpperCase(Locale.ROOT));
        if (entityPresence == EntityPresence.MUST_BE_NULL && request.hasEntity()) {
            if (suppressExceptions) {
                LOGGER.warning(LocalizationMessages.ERROR_HTTP_METHOD_ENTITY_NOT_NULL(method));
            } else {
                throw new IllegalStateException(LocalizationMessages.ERROR_HTTP_METHOD_ENTITY_NOT_NULL(method));
            }
        } else if (entityPresence == EntityPresence.MUST_BE_PRESENT && !request.hasEntity()) {
            if (suppressExceptions) {
                LOGGER.warning(LocalizationMessages.ERROR_HTTP_METHOD_ENTITY_NULL(method));
            } else {
                throw new IllegalStateException(LocalizationMessages.ERROR_HTTP_METHOD_ENTITY_NULL(method));
            }
        }
    }

    /**
     * Jersey-specific {@link javax.ws.rs.client.Invocation.Builder client invocation builder}.
     */
    public static class Builder implements javax.ws.rs.client.Invocation.Builder {

        private final ClientRequest requestContext;

        /**
         * Create new Jersey-specific client invocation builder.
         *
         * @param uri           invoked request URI.
         * @param configuration Jersey client configuration.
         */
        protected Builder(final URI uri, final ClientConfig configuration) {
            this.requestContext = new ClientRequest(uri, configuration, new MapPropertiesDelegate());
        }

        /**
         * Returns a reference to the mutable request context to be invoked.
         *
         * @return mutable request context to be invoked.
         */
        ClientRequest request() {
            return requestContext;
        }

        private void storeEntity(final Entity<?> entity) {
            if (entity != null) {
                requestContext.variant(entity.getVariant());
                requestContext.setEntity(entity.getEntity());
                requestContext.setEntityAnnotations(entity.getAnnotations());
            }
        }

        @Override
        public JerseyInvocation build(final String method) {
            requestContext.setMethod(method);
            return new JerseyInvocation(this, true);
        }

        @Override
        public JerseyInvocation build(final String method, final Entity<?> entity) {
            requestContext.setMethod(method);
            storeEntity(entity);
            return new JerseyInvocation(this, true);
        }

        @Override
        public JerseyInvocation buildGet() {
            requestContext.setMethod("GET");
            return new JerseyInvocation(this, true);
        }

        @Override
        public JerseyInvocation buildDelete() {
            requestContext.setMethod("DELETE");
            return new JerseyInvocation(this, true);
        }

        @Override
        public JerseyInvocation buildPost(final Entity<?> entity) {
            requestContext.setMethod("POST");
            storeEntity(entity);
            return new JerseyInvocation(this, true);
        }

        @Override
        public JerseyInvocation buildPut(final Entity<?> entity) {
            requestContext.setMethod("PUT");
            storeEntity(entity);
            return new JerseyInvocation(this, true);
        }

        @Override
        public javax.ws.rs.client.AsyncInvoker async() {
            return new AsyncInvoker(this);
        }

        @Override
        public Builder accept(final String... mediaTypes) {
            requestContext.accept(mediaTypes);
            return this;
        }

        @Override
        public Builder accept(final MediaType... mediaTypes) {
            requestContext.accept(mediaTypes);
            return this;
        }

        @Override
        public Invocation.Builder acceptEncoding(final String... encodings) {
            requestContext.getHeaders().addAll(HttpHeaders.ACCEPT_ENCODING, (Object[]) encodings);
            return this;
        }

        @Override
        public Builder acceptLanguage(final Locale... locales) {
            requestContext.acceptLanguage(locales);
            return this;
        }

        @Override
        public Builder acceptLanguage(final String... locales) {
            requestContext.acceptLanguage(locales);
            return this;
        }

        @Override
        public Builder cookie(final Cookie cookie) {
            requestContext.cookie(cookie);
            return this;
        }

        @Override
        public Builder cookie(final String name, final String value) {
            requestContext.cookie(new Cookie(name, value));
            return this;
        }

        @Override
        public Builder cacheControl(final CacheControl cacheControl) {
            requestContext.cacheControl(cacheControl);
            return this;
        }

        @Override
        public Builder header(final String name, final Object value) {
            final MultivaluedMap<String, Object> headers = requestContext.getHeaders();

            if (value == null) {
                headers.remove(name);
            } else {
                headers.add(name, value);
            }

            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(name)) {
                requestContext.ignoreUserAgent(value == null);
            }

            return this;
        }

        @Override
        public Builder headers(final MultivaluedMap<String, Object> headers) {
            requestContext.replaceHeaders(headers);
            return this;
        }

        @Override
        public Response get() throws ProcessingException {
            return method("GET");
        }

        @Override
        public <T> T get(final Class<T> responseType) throws ProcessingException, WebApplicationException {
            return method("GET", responseType);
        }

        @Override
        public <T> T get(final GenericType<T> responseType) throws ProcessingException, WebApplicationException {
            return method("GET", responseType);
        }

        @Override
        public Response put(final Entity<?> entity) throws ProcessingException {
            return method("PUT", entity);
        }

        @Override
        public <T> T put(final Entity<?> entity, final Class<T> responseType)
                throws ProcessingException, WebApplicationException {
            return method("PUT", entity, responseType);
        }

        @Override
        public <T> T put(final Entity<?> entity, final GenericType<T> responseType)
                throws ProcessingException, WebApplicationException {
            return method("PUT", entity, responseType);
        }

        @Override
        public Response post(final Entity<?> entity) throws ProcessingException {
            return method("POST", entity);
        }

        @Override
        public <T> T post(final Entity<?> entity, final Class<T> responseType)
                throws ProcessingException, WebApplicationException {
            return method("POST", entity, responseType);
        }

        @Override
        public <T> T post(final Entity<?> entity, final GenericType<T> responseType)
                throws ProcessingException, WebApplicationException {
            return method("POST", entity, responseType);
        }

        @Override
        public Response delete() throws ProcessingException {
            return method("DELETE");
        }

        @Override
        public <T> T delete(final Class<T> responseType) throws ProcessingException, WebApplicationException {
            return method("DELETE", responseType);
        }

        @Override
        public <T> T delete(final GenericType<T> responseType) throws ProcessingException, WebApplicationException {
            return method("DELETE", responseType);
        }

        @Override
        public Response head() throws ProcessingException {
            return method("HEAD");
        }

        @Override
        public Response options() throws ProcessingException {
            return method("OPTIONS");
        }

        @Override
        public <T> T options(final Class<T> responseType) throws ProcessingException, WebApplicationException {
            return method("OPTIONS", responseType);
        }

        @Override
        public <T> T options(final GenericType<T> responseType) throws ProcessingException, WebApplicationException {
            return method("OPTIONS", responseType);
        }

        @Override
        public Response trace() throws ProcessingException {
            return method("TRACE");
        }

        @Override
        public <T> T trace(final Class<T> responseType) throws ProcessingException, WebApplicationException {
            return method("TRACE", responseType);
        }

        @Override
        public <T> T trace(final GenericType<T> responseType) throws ProcessingException, WebApplicationException {
            return method("TRACE", responseType);
        }

        @Override
        public Response method(final String name) throws ProcessingException {
            requestContext.setMethod(name);
            return new JerseyInvocation(this).invoke();
        }

        @Override
        public <T> T method(final String name, final Class<T> responseType) throws ProcessingException, WebApplicationException {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            requestContext.setMethod(name);
            return new JerseyInvocation(this).invoke(responseType);
        }

        @Override
        public <T> T method(final String name, final GenericType<T> responseType)
                throws ProcessingException, WebApplicationException {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            requestContext.setMethod(name);
            return new JerseyInvocation(this).invoke(responseType);
        }

        @Override
        public Response method(final String name, final Entity<?> entity) throws ProcessingException {
            requestContext.setMethod(name);
            storeEntity(entity);
            return new JerseyInvocation(this).invoke();
        }

        @Override
        public <T> T method(final String name, final Entity<?> entity, final Class<T> responseType)
                throws ProcessingException, WebApplicationException {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            requestContext.setMethod(name);
            storeEntity(entity);
            return new JerseyInvocation(this).invoke(responseType);
        }

        @Override
        public <T> T method(final String name, final Entity<?> entity, final GenericType<T> responseType)
                throws ProcessingException, WebApplicationException {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            requestContext.setMethod(name);
            storeEntity(entity);
            return new JerseyInvocation(this).invoke(responseType);
        }

        @Override
        public Builder property(final String name, final Object value) {
            requestContext.setProperty(name, value);
            return this;
        }

        @Override
        public CompletionStageRxInvoker rx() {
            return rx(JerseyCompletionStageRxInvoker.class);
        }

        @Override
        public <T extends RxInvoker> T rx(Class<T> clazz) {
            if (clazz == JerseyCompletionStageRxInvoker.class) {
                final ExecutorService configured = request().getClientConfig().getExecutorService();
                if (configured == null) {
                    final ExecutorService provided = executorService();
                    if (provided != null) {
                        ((ClientConfig) request().getConfiguration()).executorService(provided);
                    }
                }
                return (T) new JerseyCompletionStageRxInvoker(this);
            }
            return createRxInvoker(clazz, executorService());
        }

        private <T extends RxInvoker> T rx(Class<T> clazz, ExecutorService executorService) {
            if (executorService == null) {
                throw new IllegalArgumentException(LocalizationMessages.NULL_INPUT_PARAMETER("executorService"));
            }

            return createRxInvoker(clazz, executorService);
        }

        // get executor service from explicit configuration; if not available, get executor service from provider
        private ExecutorService executorService() {
            final ExecutorService result = request().getClientConfig().getExecutorService();

            if (result != null) {
                return result;
            }

            final List<ServiceHolder<ExecutorServiceProvider>> serviceHolders =
                    this.requestContext.getInjectionManager().getAllServiceHolders(ExecutorServiceProvider.class);

            BestServiceHolder best = serviceHolders.stream()
                    .map(BestServiceHolder::new).sorted((a, b) -> a.isBetterThen(b) ? -1 : 1).findFirst().get();

            return best.provider.getExecutorService();
        }

        /*
         * Priority goes to: 1) user async
         *                   2) user nonasync
         *                   3) default async
         */
        private static final class BestServiceHolder {
            private final ExecutorServiceProvider provider;
            private final int value;

            private BestServiceHolder(ServiceHolder<ExecutorServiceProvider> holder) {
                provider = holder.getInstance();
                boolean isDefault = DefaultClientAsyncExecutorProvider.class.equals(holder.getImplementationClass())
                        || ClientExecutorProvidersConfigurator.ClientExecutorServiceProvider.class
                                .equals(holder.getImplementationClass());
                boolean isAsync = holder.getImplementationClass().getAnnotation(ClientAsyncExecutor.class) != null;
                value = 10 * (isDefault ? 0 : 1) + (isAsync ? 1 : 0);
            }

            public boolean isBetterThen(BestServiceHolder other) {
                return this.value > other.value;
            }
        }

        /**
         * Create {@link RxInvoker} from provided {@code RxInvoker} subclass.
         * <p>
         * The method does a lookup for {@link RxInvokerProvider}, which provides given {@code RxInvoker} subclass
         * and if found, calls {@link RxInvokerProvider#getRxInvoker(SyncInvoker, ExecutorService)}
         *
         * @param clazz           {@code RxInvoker} subclass to be created.
         * @param executorService to be passed to the factory method invocation.
         * @param <T>             {@code RxInvoker} subclass to be returned.
         * @return thread safe instance of {@code RxInvoker} subclass.
         * @throws IllegalStateException when provider for given class is not registered.
         */
        private <T extends RxInvoker> T createRxInvoker(Class<? extends RxInvoker> clazz,
                                                        ExecutorService executorService) {
            if (clazz == null) {
                throw new IllegalArgumentException(LocalizationMessages.NULL_INPUT_PARAMETER("clazz"));
            }

            Iterable<RxInvokerProvider> allProviders = Providers.getAllProviders(
                    this.requestContext.getInjectionManager(),
                    RxInvokerProvider.class);

            for (RxInvokerProvider invokerProvider : allProviders) {
                if (invokerProvider.isProviderFor(clazz)) {

                    RxInvoker rxInvoker = invokerProvider.getRxInvoker(this, executorService);

                    if (rxInvoker == null) {
                        throw new IllegalStateException(LocalizationMessages.CLIENT_RX_PROVIDER_NULL());
                    }

                    return (T) rxInvoker;
                }
            }

            throw new IllegalStateException(
                    LocalizationMessages.CLIENT_RX_PROVIDER_NOT_REGISTERED(clazz.getSimpleName()));
        }
    }

    /* package */ static class AsyncInvoker extends CompletableFutureAsyncInvoker implements javax.ws.rs.client.AsyncInvoker {

        private final JerseyInvocation.Builder builder;

        /* package */ AsyncInvoker(final JerseyInvocation.Builder request) {
            this.builder = request;
            this.builder.requestContext.setAsynchronous(true);
        }

        @Override
        public CompletableFuture<Response> method(final String name) {
            builder.requestContext.setMethod(name);
            return (CompletableFuture<Response>) new JerseyInvocation(builder).submit();
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final Class<T> responseType) {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            builder.requestContext.setMethod(name);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(responseType);
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final GenericType<T> responseType) {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            builder.requestContext.setMethod(name);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(responseType);
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final InvocationCallback<T> callback) {
            builder.requestContext.setMethod(name);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(callback);
        }

        @Override
        public CompletableFuture<Response> method(final String name, final Entity<?> entity) {
            builder.requestContext.setMethod(name);
            builder.storeEntity(entity);
            return (CompletableFuture<Response>) new JerseyInvocation(builder).submit();
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final Entity<?> entity, final Class<T> responseType) {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            builder.requestContext.setMethod(name);
            builder.storeEntity(entity);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(responseType);
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final Entity<?> entity, final GenericType<T> responseType) {
            if (responseType == null) {
                throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
            }
            builder.requestContext.setMethod(name);
            builder.storeEntity(entity);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(responseType);
        }

        @Override
        public <T> CompletableFuture<T> method(final String name, final Entity<?> entity, final InvocationCallback<T> callback) {
            builder.requestContext.setMethod(name);
            builder.storeEntity(entity);
            return (CompletableFuture<T>) new JerseyInvocation(builder).submit(callback);
        }
    }

    private ClientRequest requestForCall(final ClientRequest requestContext) {
        return copyRequestContext ? new ClientRequest(requestContext) : requestContext;
    }

    @Override
    public Response invoke() throws ProcessingException, WebApplicationException {
        final ClientRuntime runtime = request().getClientRuntime();
        final RequestScope requestScope = runtime.getRequestScope();

        return runInScope(((Producer<Response>) () ->
                        new InboundJaxrsResponse(runtime.invoke(requestForCall(requestContext)), requestScope)),
                        requestScope);
    }

    @Override
    public <T> T invoke(final Class<T> responseType) throws ProcessingException, WebApplicationException {
        if (responseType == null) {
            throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
        }
        final ClientRuntime runtime = request().getClientRuntime();
        final RequestScope requestScope = runtime.getRequestScope();

        return runInScope(() ->
                translate(runtime.invoke(requestForCall(requestContext)), requestScope, responseType), requestScope);
    }

    @Override
    public <T> T invoke(final GenericType<T> responseType) throws ProcessingException, WebApplicationException {
        if (responseType == null) {
            throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
        }
        final ClientRuntime runtime = request().getClientRuntime();
        final RequestScope requestScope = runtime.getRequestScope();

        return runInScope(() ->
                translate(runtime.invoke(requestForCall(requestContext)), requestScope, responseType), requestScope);
    }

    private <T> T runInScope(Producer<T> producer, RequestScope scope) throws ProcessingException, WebApplicationException {
        return scope.runInScope(() -> call(producer, scope));
    }

    private <T> T call(Producer<T> producer, RequestScope scope)
            throws ProcessingException, WebApplicationException {
        try {
            return producer.call();
        } catch (final ClientResponseProcessingException crpe) {
            throw new ResponseProcessingException(
                    translate(crpe.getClientResponse(), scope, Response.class), crpe.getCause()
            );
        } catch (final ProcessingException ex) {
            if (WebApplicationException.class.isInstance(ex.getCause())) {
                throw (WebApplicationException) ex.getCause();
            }
            throw ex;
        }
    }

    @Override
    public Future<Response> submit() {
        final CompletableFuture<Response> responseFuture = new CompletableFuture<>();
        final ClientRuntime runtime = request().getClientRuntime();
        runtime.submit(runtime.createRunnableForAsyncProcessing(requestForCall(requestContext),
                new InvocationResponseCallback<>(responseFuture, (request, scope) -> translate(request, scope, Response.class))));

        return responseFuture;
    }

    @Override
    public <T> Future<T> submit(final Class<T> responseType) {
        if (responseType == null) {
            throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
        }
        final CompletableFuture<T> responseFuture = new CompletableFuture<>();
        final ClientRuntime runtime = request().getClientRuntime();

        runtime.submit(runtime.createRunnableForAsyncProcessing(requestForCall(requestContext),
                new InvocationResponseCallback<T>(responseFuture, (request, scope) -> translate(request, scope, responseType))));

        return responseFuture;
    }

    private <T> T translate(final ClientResponse response, final RequestScope scope, final Class<T> responseType)
            throws ProcessingException {
        if (responseType == Response.class) {
            return responseType.cast(new InboundJaxrsResponse(response, scope));
        }

        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            try {
                return response.readEntity(responseType);
            } catch (final ProcessingException ex) {
                if (ex.getClass() == ProcessingException.class) {
                    throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope), ex.getCause());
                }
                throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope), ex);
            } catch (final WebApplicationException ex) {
                throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope), ex);
            } catch (final Exception ex) {
                throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope),
                        LocalizationMessages.UNEXPECTED_ERROR_RESPONSE_PROCESSING(), ex);
            }
        } else {
            throw convertToException(new InboundJaxrsResponse(response, scope));
        }
    }

    @Override
    public <T> Future<T> submit(final GenericType<T> responseType) {
        if (responseType == null) {
            throw new IllegalArgumentException(LocalizationMessages.RESPONSE_TYPE_IS_NULL());
        }
        final CompletableFuture<T> responseFuture = new CompletableFuture<>();
        final ClientRuntime runtime = request().getClientRuntime();

        runtime.submit(runtime.createRunnableForAsyncProcessing(requestForCall(requestContext),
                new InvocationResponseCallback<T>(responseFuture, (request, scope) -> translate(request, scope, responseType))));

        return responseFuture;
    }

    private <T> T translate(final ClientResponse response, final RequestScope scope, final GenericType<T> responseType)
            throws ProcessingException {
        if (responseType.getRawType() == Response.class) {
            //noinspection unchecked
            return (T) new InboundJaxrsResponse(response, scope);
        }

        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            try {
                return response.readEntity(responseType);
            } catch (final ProcessingException ex) {
                throw new ResponseProcessingException(
                        new InboundJaxrsResponse(response, scope),
                        ex.getCause() != null ? ex.getCause() : ex);
            } catch (final WebApplicationException ex) {
                throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope), ex);
            } catch (final Exception ex) {
                throw new ResponseProcessingException(new InboundJaxrsResponse(response, scope),
                        LocalizationMessages.UNEXPECTED_ERROR_RESPONSE_PROCESSING(), ex);
            }
        } else {
            throw convertToException(new InboundJaxrsResponse(response, scope));
        }
    }

    @Override
    public <T> Future<T> submit(final InvocationCallback<T> callback) {
        return submit(null, callback);
    }

    /**
     * Submit the request for an asynchronous invocation and register an
     * {@link InvocationCallback} to process the future result of the invocation.
     * <p>
     * Response type in this case is taken from {@code responseType} param (if not {@code null}) rather
     * than from {@code callback}. This allows to pass callbacks like {@code new InvocationCallback&lt;&gt() {...}}.
     * </p>
     *
     * @param <T>          response type
     * @param responseType response type that is used instead of obtaining types from {@code callback}.
     * @param callback     invocation callback for asynchronous processing of the
     *                     request invocation result.
     * @return future response object of the specified type as a result of the
     * request invocation.
     */
    public <T> Future<T> submit(final GenericType<T> responseType, final InvocationCallback<T> callback) {
        final CompletableFuture<T> responseFuture = new CompletableFuture<>();

        try {
            final ReflectionHelper.DeclaringClassInterfacePair pair =
                    ReflectionHelper.getClass(callback.getClass(), InvocationCallback.class);

            final Type callbackParamType;
            final Class<T> callbackParamClass;

            if (responseType == null) {
                // If we don't have response use callback to obtain param types.
                final Type[] typeArguments = ReflectionHelper.getParameterizedTypeArguments(pair);
                if (typeArguments == null || typeArguments.length == 0) {
                    callbackParamType = Object.class;
                } else {
                    callbackParamType = typeArguments[0];
                }
                callbackParamClass = ReflectionHelper.erasure(callbackParamType);
            } else {
                callbackParamType = responseType.getType();
                callbackParamClass = ReflectionHelper.erasure(responseType.getRawType());
            }

            final ResponseCallback responseCallback = new ResponseCallback() {

                @Override
                public void completed(final ClientResponse response, final RequestScope scope) {
                    if (responseFuture.isCancelled()) {
                        response.close();
                        failed(new ProcessingException(
                                new CancellationException(LocalizationMessages.ERROR_REQUEST_CANCELLED())));
                        return;
                    }

                    final T result;
                    if (callbackParamClass == Response.class) {
                        result = callbackParamClass.cast(new InboundJaxrsResponse(response, scope));
                        responseFuture.complete(result);
                        callback.completed(result);
                    } else if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                        result = response.readEntity(new GenericType<T>(callbackParamType));
                        responseFuture.complete(result);
                        callback.completed(result);
                    } else {
                        failed(convertToException(new InboundJaxrsResponse(response, scope)));
                    }
                }

                @Override
                public void failed(final ProcessingException error) {
                    Exception called = null;
                    try {
                        if (error.getCause() instanceof WebApplicationException) {
                            responseFuture.completeExceptionally(error.getCause());
                        } else if (!responseFuture.isCancelled()) {
                            try {
                                call(() -> { throw error; }, null);
                            } catch (Exception ex) {
                                called = ex;
                                responseFuture.completeExceptionally(ex);
                            }
                        }
                    } finally {
                        callback.failed(
                                error.getCause() instanceof CancellationException
                                        ? error.getCause()
                                        : called != null ? called : error
                        );
                    }
                }
            };
            final ClientRuntime runtime = request().getClientRuntime();
            runtime.submit(runtime.createRunnableForAsyncProcessing(requestForCall(requestContext), responseCallback));
        } catch (final Throwable error) {
            final ProcessingException ce;
            //noinspection ChainOfInstanceofChecks
            if (error instanceof ClientResponseProcessingException) {
                ce = new ProcessingException(error.getCause());
                responseFuture.completeExceptionally(ce);
            } else if (error instanceof ProcessingException) {
                ce = (ProcessingException) error;
                responseFuture.completeExceptionally(ce);
            } else if (error instanceof WebApplicationException) {
                ce = new ProcessingException(error);
                responseFuture.completeExceptionally(error);
            } else {
                ce = new ProcessingException(error);
                responseFuture.completeExceptionally(ce);
            }
            callback.failed(ce);
        }

        return responseFuture;
    }

    @Override
    public JerseyInvocation property(final String name, final Object value) {
        requestContext.setProperty(name, value);
        return this;
    }

    private ProcessingException convertToException(final Response response) {
        // Use an empty response if ignoring response in exception
        final int statusCode = response.getStatus();
        final Response finalResponse = ignoreResponseException ? Response.status(statusCode).build() : response;

        try {
            // Buffer and close entity input stream (if any) to prevent
            // leaking connections (see JERSEY-2157).
            response.bufferEntity();

            final WebApplicationException webAppException;
            final Response.Status status = Response.Status.fromStatusCode(statusCode);

            if (status == null) {
                final Response.Status.Family statusFamily = finalResponse.getStatusInfo().getFamily();
                webAppException = createExceptionForFamily(finalResponse, statusFamily);
            } else {
                switch (status) {
                    case BAD_REQUEST:
                        webAppException = new BadRequestException(finalResponse);
                        break;
                    case UNAUTHORIZED:
                        webAppException = new NotAuthorizedException(finalResponse);
                        break;
                    case FORBIDDEN:
                        webAppException = new ForbiddenException(finalResponse);
                        break;
                    case NOT_FOUND:
                        webAppException = new NotFoundException(finalResponse);
                        break;
                    case METHOD_NOT_ALLOWED:
                        webAppException = new NotAllowedException(finalResponse);
                        break;
                    case NOT_ACCEPTABLE:
                        webAppException = new NotAcceptableException(finalResponse);
                        break;
                    case UNSUPPORTED_MEDIA_TYPE:
                        webAppException = new NotSupportedException(finalResponse);
                        break;
                    case INTERNAL_SERVER_ERROR:
                        webAppException = new InternalServerErrorException(finalResponse);
                        break;
                    case SERVICE_UNAVAILABLE:
                        webAppException = new ServiceUnavailableException(finalResponse);
                        break;
                    default:
                        final Response.Status.Family statusFamily = finalResponse.getStatusInfo().getFamily();
                        webAppException = createExceptionForFamily(finalResponse, statusFamily);
                }
            }

            return new ResponseProcessingException(finalResponse, webAppException);
        } catch (final Throwable t) {
            return new ResponseProcessingException(finalResponse,
                    LocalizationMessages.RESPONSE_TO_EXCEPTION_CONVERSION_FAILED(), t);
        }
    }

    private WebApplicationException createExceptionForFamily(final Response response, final Response.Status.Family statusFamily) {
        final WebApplicationException webAppException;
        switch (statusFamily) {
            case REDIRECTION:
                webAppException = new RedirectionException(response);
                break;
            case CLIENT_ERROR:
                webAppException = new ClientErrorException(response);
                break;
            case SERVER_ERROR:
                webAppException = new ServerErrorException(response);
                break;
            default:
                webAppException = new WebApplicationException(response);
        }
        return webAppException;
    }

    /**
     * Returns a reference to the mutable request context to be invoked.
     *
     * @return mutable request context to be invoked.
     */
    ClientRequest request() {
        return requestContext;
    }

    @Override
    public String toString() {
        return "JerseyInvocation [" + request().getMethod() + ' ' + request().getUri() + "]";
    }

    private class InvocationResponseCallback<R> implements ResponseCallback {
        private final CompletableFuture<R> responseFuture;
        private final BiFunction<ClientResponse, RequestScope, R> producer;

        private InvocationResponseCallback(CompletableFuture<R> responseFuture,
                                           BiFunction<ClientResponse, RequestScope, R> producer) {
            this.responseFuture = responseFuture;
            this.producer = producer;
        }

        @Override
        public void completed(final ClientResponse response, final RequestScope scope) {
            if (responseFuture.isCancelled()) {
                response.close();
                return;
            }


            try {
                responseFuture.complete(producer.apply(response, scope));
            } catch (final ProcessingException ex) {
                failed(ex);
            }
        }

        @Override
        public void failed(final ProcessingException error) {
            if (responseFuture.isCancelled()) {
                return;
            }

            try {
                call(() -> {
                    throw error;
                }, null);
            } catch (Exception exception) {
                responseFuture.completeExceptionally(exception);
            }
        }
    }
}
