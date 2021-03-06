/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.amqp.rabbit.listener.adapter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Address;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.util.Assert;


/**
 * Delegates to an {@link InvocableHandlerMethod} based on the message payload type.
 * Matches a single, non-annotated parameter or one that is annotated with {@link Payload}.
 * Matches must be unambiguous.
 *
 * @author Gary Russell
 * @since 1.5
 *
 */
public class DelegatingInvocableHandler {

	private static final ThreadLocal<Address> defaultReplyTo = new ThreadLocal<Address>();

	private final List<InvocableHandlerMethod> handlers;

	private final ConcurrentMap<Class<?>, InvocableHandlerMethod> cachedHandlers =
			new ConcurrentHashMap<Class<?>, InvocableHandlerMethod>();

	private final Map<InvocableHandlerMethod, Address> defaultReplyToForHandler =
			new HashMap<InvocableHandlerMethod, Address>();

	private final Object bean;

	private final BeanExpressionResolver resolver;

	private final BeanExpressionContext beanExpressionContext;

	/**
	 * Construct an instance with the supplied handlers for the bean.
	 * @param handlers the handlers.
	 * @param bean the bean.
	 * @param beanExpressionResolver the resolver.
	 * @param beanExpressionContext the context.
	 */
	public DelegatingInvocableHandler(List<InvocableHandlerMethod> handlers, Object bean,
			BeanExpressionResolver beanExpressionResolver, BeanExpressionContext beanExpressionContext) {
		this.handlers = new ArrayList<InvocableHandlerMethod>(handlers);
		this.bean = bean;
		this.resolver = beanExpressionResolver;
		this.beanExpressionContext = beanExpressionContext;
	}

	/**
	 * @return the bean
	 */
	public Object getBean() {
		return bean;
	}

	/**
	 * Invoke the method with the given message.
	 * @param message the message.
	 * @param providedArgs additional arguments.
	 * @throws Exception raised if no suitable argument resolver can be found,
	 * or the method raised an exception.
	 * @return the result of the invocation.
	 */
	public Object invoke(Message<?> message, Object... providedArgs) throws Exception {
		Class<? extends Object> payloadClass = message.getPayload().getClass();
		InvocableHandlerMethod handler = getHandlerForPayload(payloadClass);
		Object result = handler.invoke(message, providedArgs);
		Address replyTo = this.defaultReplyToForHandler.get(handler);
		if (replyTo != null) {
			defaultReplyTo.set(replyTo);
		}
		return result;
	}

	/**
	 * @param payloadClass the payload class.
	 * @return the handler.
	 */
	protected InvocableHandlerMethod getHandlerForPayload(Class<? extends Object> payloadClass) {
		InvocableHandlerMethod handler = this.cachedHandlers.get(payloadClass);
		if (handler == null) {
			handler = findHandlerForPayload(payloadClass);
			if (handler == null) {
				throw new AmqpException("No method found for " + payloadClass);
			}
			this.cachedHandlers.putIfAbsent(payloadClass, handler);//NOSONAR
			setupReplyTo(handler);
		}
		return handler;
	}

	private void setupReplyTo(InvocableHandlerMethod handler) {
		Method method = handler.getMethod();
		if (method != null) {
			SendTo ann = AnnotationUtils.getAnnotation(method, SendTo.class);
			if (ann != null) {
				String[] destinations = ann.value();
				if (destinations.length > 1) {
					throw new IllegalStateException("Invalid @" + SendTo.class.getSimpleName() + " annotation on '"
							+ method + "' one destination must be set (got " + Arrays.toString(destinations) + ")");
				}
				Address replyTo = destinations.length == 1 ? new Address(resolve(destinations[0])) : null;
				if (replyTo != null) {
					this.defaultReplyToForHandler.put(handler, replyTo);
				}
			}
		}

	}

	private String resolve(String value) {
		if (this.resolver != null) {
			Object newValue = this.resolver.evaluate(value, this.beanExpressionContext);
			Assert.isInstanceOf(String.class, newValue, "Invalid @SendTo expression");
			return (String) newValue;
		}
		else {
			return value;
		}
	}

	protected InvocableHandlerMethod findHandlerForPayload(Class<? extends Object> payloadClass) {
		InvocableHandlerMethod result = null;
		for (InvocableHandlerMethod handler : this.handlers) {
			if (matchHandlerMethod(payloadClass, handler)) {
				if (result != null) {
					throw new AmqpException("Ambiguous methods for payload type: " + payloadClass + ": " +
							result.getMethod().getName() + " and " + handler.getMethod().getName());
				}
				result = handler;
			}
		}
		return result;
	}

	protected boolean matchHandlerMethod(Class<? extends Object> payloadClass, InvocableHandlerMethod handler) {
		Method method = handler.getMethod();
		Annotation[][] parameterAnnotations = method.getParameterAnnotations();
		// Single param; no annotation or @Payload
		if (parameterAnnotations.length == 1) {
			MethodParameter methodParameter = new MethodParameter(method, 0);
			if (methodParameter.getParameterAnnotations().length == 0 || methodParameter.hasParameterAnnotation(Payload.class)) {
				if (methodParameter.getParameterType().isAssignableFrom(payloadClass)) {
					return true;
				}
			}
		}
		boolean foundCandidate = false;
		for (int i = 0; i < parameterAnnotations.length; i++) {
			MethodParameter methodParameter = new MethodParameter(method, i);
			if (methodParameter.getParameterAnnotations().length == 0 || methodParameter.hasParameterAnnotation(Payload.class)) {
				if (methodParameter.getParameterType().isAssignableFrom(payloadClass)) {
					if (foundCandidate) {
						throw new AmqpException("Ambiguous payload parameter for " + method.toGenericString());
					}
					foundCandidate = true;
				}
			}
		}
		return foundCandidate;
	}

	/**
	 * Return a string representation of the method that will be invoked for this payload.
	 * @param payload the payload.
	 * @return the method name.
	 */
	public String getMethodNameFor(Object payload) {
		InvocableHandlerMethod handlerForPayload = getHandlerForPayload(payload.getClass());
		return handlerForPayload == null ? "no match" : handlerForPayload.getMethod().toGenericString();//NOSONAR
	}

	/**
	 * @return the default replyTo for the invoked method, if any.
	 */
	public Address getDefaultReplyTo() {
		Address replyTo = defaultReplyTo.get();
		if (replyTo != null) {
			defaultReplyTo.remove();
		}
		return replyTo;
	}

}
