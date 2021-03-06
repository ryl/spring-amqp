/*
 * Copyright 2016 the original author or authors.
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
package org.springframework.amqp.rabbit.listener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.rabbit.listener.exception.FatalListenerStartupException;
import org.springframework.amqp.rabbit.test.BrokerRunning;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Gary Russell
 * @since 1.6
 *
 */
public class ContainerInitializationTests {

	@Rule
	public BrokerRunning brokerRunning = BrokerRunning.isRunningWithEmptyQueues("test.mismatch");

	@After
	public void tearDown() {
		brokerRunning.removeTestQueues();
	}

	@Test
	public void testNoAdmin() throws Exception {
		try {
			AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config0.class);
			context.close();
			fail("expected initialization failure");
		}
		catch (ApplicationContextException e) {
			assertThat(e.getCause().getCause(), instanceOf(IllegalStateException.class));
			assertThat(e.getMessage(), containsString("When 'mismatchedQueuesFatal' is 'true', there must be "
				+ "exactly one RabbitAdmin in the context or you must inject one into this container; found: 0"));
		}
	}

	@Test
	public void testMismatchedQueue() {
		try {
			AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config1.class);
			context.close();
			fail("expected initialization failure");
		}
		catch (ApplicationContextException e) {
			assertThat(e.getCause(), instanceOf(FatalListenerStartupException.class));
		}
	}

	@Test
	public void testMismatchedQueueDuringRestart() throws Exception {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config2.class);
		RabbitAdmin admin = context.getBean(RabbitAdmin.class);
		admin.deleteQueue("test.mismatch");
		admin.declareQueue(new Queue("test.mismatch", false, false, true));
		SimpleMessageListenerContainer container = context.getBean(SimpleMessageListenerContainer.class);
		int n = 0;
		while (n++ < 100 && container.isRunning()) {
			Thread.sleep(100);
		}
		assertFalse(container.isRunning());
		context.close();
	}

	@Configuration
	static class Config0 {

		@Bean
		public ConnectionFactory connectionFactory() {
			return new CachingConnectionFactory("localhost");
		}

		@Bean
		public SimpleMessageListenerContainer container() {
			SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory());
			container.setQueueNames("test.mismatch");
			container.setMessageListener(new MessageListenerAdapter(new Object() {

				@SuppressWarnings("unused")
				public void handleMessage(Message m) {
				}

			}));
			container.setMismatchedQueuesFatal(true);
			return container;
		}

		@Bean
		public Queue queue() {
			return new Queue("test.mismatch", false, false, true); // mismatched
		}

	}

	@Configuration
	static class Config1 extends Config0 {

		@Bean
		public RabbitAdmin admin() {
			return new RabbitAdmin(connectionFactory());
		}

	}

	@Configuration
	static class Config2 extends Config1 {

		@Override
		@Bean
		public Queue queue() {
			return new Queue("test.mismatch", true, false, false);
		}

	}

}
