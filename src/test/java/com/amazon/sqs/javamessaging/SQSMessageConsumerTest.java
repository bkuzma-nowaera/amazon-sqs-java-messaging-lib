/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.sqs.javamessaging;

import com.amazon.sqs.javamessaging.acknowledge.AcknowledgeMode;
import com.amazon.sqs.javamessaging.acknowledge.Acknowledger;
import com.amazon.sqs.javamessaging.acknowledge.NegativeAcknowledger;
import com.amazon.sqs.javamessaging.util.SQSMessagingClientThreadFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Test the SQSMessageConsumerPrefetchTest class
 */
public class SQSMessageConsumerTest {

    private static final String QUEUE_URL_1 = "QueueUrl1";
    private static final String QUEUE_NAME = "QueueName";

    private SQSMessageConsumer consumer;
    private SQSConnection sqsConnection;
    private SQSSession sqsSession;
    private SQSSessionCallbackScheduler sqsSessionRunnable;
    private Acknowledger acknowledger;

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(5);
    private SQSMessageConsumerPrefetch sqsMessageConsumerPrefetch;
    private NegativeAcknowledger negativeAcknowledger;
    private SQSMessagingClientThreadFactory threadFactory;
    private SQSQueueDestination destination;

    @BeforeEach
    public void setup() throws JMSException {
        sqsConnection = mock(SQSConnection.class);

        sqsSession = spy(new SQSSession(sqsConnection, AcknowledgeMode.ACK_AUTO));//mock(SQSSession.class);
        sqsSessionRunnable = mock(SQSSessionCallbackScheduler.class);

        acknowledger = mock(Acknowledger.class);

        negativeAcknowledger = mock(NegativeAcknowledger.class);

        threadFactory = new SQSMessagingClientThreadFactory("testTask", true);

        destination = new SQSQueueDestination(QUEUE_NAME, QUEUE_URL_1);

        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory));


        sqsMessageConsumerPrefetch = mock(SQSMessageConsumerPrefetch.class);
    }

    /**
     * Test the message selector is not supported
     */
    @Test
    public void testGetMessageSelectorNotSupported() {
        assertThatThrownBy(() -> consumer.getMessageSelector())
                .isInstanceOf(JMSException.class)
                .hasMessage("Unsupported Method");
    }

    /**
     * Test stop is a no op if already closed
     */
    @Test
    public void testStopNoOpIfAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer.close();

        /*
         * stop consumer
         */
        consumer.stopPrefetch();

        /*
         * Verify results
         */
        verifyNoMoreInteractions(sqsMessageConsumerPrefetch);
    }

    /**
     * Test close blocks on in progress callback
     */
    @Test
    public void testCloseBlocksInProgressCallback() throws InterruptedException, JMSException {
        /*
         * Set up the latches
         */
        final CountDownLatch beforeConsumerStopCall = new CountDownLatch(1);
        final CountDownLatch passedConsumerStopCall = new CountDownLatch(1);

        consumer = new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch);

        sqsSession.start();
        sqsSession.startingCallback(consumer);

        // Run another thread that tries to close the consumer while activeConsumerInCallback is set
        executorService.execute(() -> {
            beforeConsumerStopCall.countDown();
            try {
                consumer.close();
            } catch (JMSException e) {
                fail();
            }
            passedConsumerStopCall.countDown();
        });

        beforeConsumerStopCall.await();
        Thread.sleep(10);
        // Ensure that we wait on activeConsumerInCallback
        assertFalse(passedConsumerStopCall.await(2, TimeUnit.SECONDS));

        // Release the activeConsumerInCallback
        sqsSession.finishedCallback();

        // Ensure that the consumer close completed
        passedConsumerStopCall.await();

        assertTrue(consumer.closed);
    }


    /**
     * Test Start is a no op if already closed
     */
    @Test
    public void testStartNoOpIfAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer.close();

        /*
         * start consumer
         */
        consumer.startPrefetch();

        /*
         * Verify results
         */
        verifyNoMoreInteractions(sqsMessageConsumerPrefetch);
    }

    /**
     * Test do close results in no op when the consumer is already closed
     */
    @Test
    public void testDoCloseNoOpWhenAlreadyClosed() {
        /*
         * Set up consumer
         */
        consumer.closed = true;

        /*
         * Do close consumer
         */
        consumer.doClose();

        /*
         * Verify results
         */
        verifyNoMoreInteractions(sqsSession);
    }


    /**
     * Test do close
     */
    @Test
    public void testDoClose() {
        consumer = new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch);

        /*
         * Do close consumer
         */
        consumer.doClose();

        /*
         * Verify results
         */
        verify(sqsSession).removeConsumer(consumer);
        verify(sqsMessageConsumerPrefetch).close();
    }


    /**
     * Test close results in no op when the consumer is already closed
     */
    @Test
    public void testCloseNoOpWhenAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer.closed = true;

        /*
         * Close consumer
         */
        consumer.close();

        /*
         * Verify results
         */

        verify(consumer, never()).doClose();
        verify(sqsSessionRunnable, never()).setConsumerCloseAfterCallback(any(SQSMessageConsumer.class));
    }

    /**
     * Test when consumer is closed by the message listener that is running on the callback thread
     * we do not close but set a consumer close after callback
     */
    @Test
    public void testCloseCalledFromCallbackExecutionThread() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        when(sqsSession.isActiveCallbackSessionThread())
                .thenReturn(true);

        /*
         * Close consumer
         */
        consumer.close();

        /*
         * Verify results
         */
        verify(consumer, never()).doClose();
        verify(sqsSessionRunnable).setConsumerCloseAfterCallback(consumer);
    }

    /**
     * Test consumer close
     */
    @Test
    public void testClose() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        /*
         * Close consumer
         */
        consumer.close();

        /*
         * Verify results
         */
        verify(consumer).doClose();
        verify(sqsSessionRunnable, never()).setConsumerCloseAfterCallback(consumer);
    }

    /**
     * Test set message listener fails when consumer is already closed
     */
    @Test
    public void testSetMessageListenerAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        consumer.close();

        MessageListener msgListener = mock(MessageListener.class);

        /*
         * Set message listener on a consumer
         */
        assertThatThrownBy(() -> consumer.setMessageListener(msgListener))
                .isInstanceOf(JMSException.class)
                .hasMessage("Consumer is closed");
    }

    /**
     * Test receive fails when consumer is already closed
     */
    @Test
    public void testReceiveAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        consumer.close();

        /*
         * Call receive
         */
        assertThatThrownBy(() -> consumer.receive())
                .isInstanceOf(JMSException.class)
                .hasMessage("Consumer is closed");

    }

    /**
     * Test set message listener fails when consumer is already closed
     */
    @Test
    public void testReceiveWithTimeoutAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));
        consumer.close();

        long timeout = 10;

        /*
         * Call receive with timeout
         */
        assertThatThrownBy(() -> consumer.receive(timeout))
                .isInstanceOf(JMSException.class)
                .hasMessage("Consumer is closed");

    }

    /**
     * Test set message listener fails when consumer is already closed
     */
    @Test
    public void testReceiveNoWaitAlreadyClosed() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));
        consumer.close();

        /*
         * Call receive no wait
         */
        assertThatThrownBy(() -> consumer.receiveNoWait())
                .isInstanceOf(JMSException.class)
                .hasMessage("Consumer is closed");
    }

    /**
     * Test set message listener
     */
    @Test
    public void testSetMessageListener() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));
        MessageListener msgListener = mock(MessageListener.class);

        /*
         * Set message listener on a consumer
         */
        consumer.setMessageListener(msgListener);

        /*
         * Verify results
         */
        verify(sqsMessageConsumerPrefetch).setMessageListener(msgListener);
    }

    /**
     * Test get message listener
     */
    @Test
    public void testGetMessageListener() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        /*
         * Get message listener on a consumer
         */
        consumer.getMessageListener();

        /*
         * Verify results
         */
        verify(sqsMessageConsumerPrefetch).getMessageListener();
    }

    /**
     * Test get message listener
     */
    @Test
    public void testGetQueue() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        assertEquals(destination, consumer.getQueue());
    }

    /**
     * Test receive
     */
    @Test
    public void testReceive() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        /*
         * Call receive
         */
        consumer.receive();

        /*
         * Verify results
         */
        verify(sqsMessageConsumerPrefetch).receive();
    }

    /**
     * Test receive with timeout
     */
    @Test
    public void testReceiveWithTimeout() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        long timeout = 10;

        /*
         * Call receive with timeout
         */
        consumer.receive(timeout);

        /*
         * Verify results
         */
        verify(sqsMessageConsumerPrefetch).receive(timeout);
    }

    /**
     * Test receive no wait
     */
    @Test
    public void testReceiveNoWait() throws JMSException {
        /*
         * Set up consumer
         */
        consumer = spy(new SQSMessageConsumer(sqsConnection, sqsSession, sqsSessionRunnable,
                destination, acknowledger, negativeAcknowledger, threadFactory, sqsMessageConsumerPrefetch));

        /*
         * Call receive no wait
         */
        consumer.receiveNoWait();

        /*
         * Verify results
         */
        verify(sqsMessageConsumerPrefetch).receiveNoWait();
    }
}
