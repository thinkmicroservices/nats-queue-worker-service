package com.thinkmicroservices.natsservice.handler;

import io.nats.client.Connection.Status;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import javax.annotation.PostConstruct;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The QueueMessageHandler listens for Fire and Forget messages, and uses the
 * message to change the operation of the queue worker. It also listens for 
 * queue group messages, processes the message with the current FnF operation, 
 * and returns the processed message to the originator using the message's 
 * <i>replyTo</i> inbox subject.
 * @author cwoodward
 */
@Slf4j
@Component
public class QueueMessageHandler implements ConnectionListener {

    @Value("${nats.servers}")
    private String[] servers;
    @Value("${queue.worker.name}")
    private String queueWorkerName;
    private Connection connection;
    private Dispatcher queueDispatcher;
    private Dispatcher subscribeDispatcher;
    private String lastFnFMessage;

    private static final String NATS_PROTOCOL_STRING = "nats://";
    private static final String FNF_SUBJECT = "nats.fnf";
    
    private static final String UPPER_CASE = "uc";
    private static final String REVERSE = "rev";
    private static final String LOWER_CASE = "lc";
    private static final String CAPITALIZE = "cap";
    
    private static final String QUEUE_SUBJECT = "nats.queue";
    private static final String QUEUE_GROUP = "queue-workers";

    private static final String RESPONSE_TEMPLATE_STRING = "<%s> transformed into (%s)";

    private Connection getConnection() throws IOException, InterruptedException {
        if ((connection == null) || (connection.getStatus() == Status.DISCONNECTED)) {
            //Options o = new Options.Builder().server("nats://nats-1:4222").server("nats://nats-2:4222").maxReconnects(-1).build();
            Options.Builder connectionBuilder = new Options.Builder().connectionListener(this);

            for (String server : servers) {
                String natsServer = NATS_PROTOCOL_STRING + server;
                log.info("adding nats server:" + natsServer);
                connectionBuilder.server(natsServer).maxReconnects(-1);
            }

            connection = Nats.connect(connectionBuilder.build());
        }
        log.info("return connection:" + connection);

        return connection;
    }

    @Override
    public void connectionEvent(Connection cnctn, Events event) {
        log.info("Connection Event:" + event);

        switch (event) {

            case CONNECTED:
                log.info("CONNECTED!");
                break;
            case DISCONNECTED:
                try {
                    connection = null;
                    getConnection();
                } catch (Exception ex) {
                    log.error(ex.getMessage(), ex);

                }
                break;
            case RECONNECTED:
                log.info("RECONNECTED!");
                break;
            case RESUBSCRIBED:
                log.info("RESUBSCRIBED!");
                break;

        }

    }

    /**
     *
     * @throws IOException
     * @throws InterruptedException
     */
    private void createQueueWorkerDispatcher() throws IOException, InterruptedException {
        log.info("create Queue Worker dispatcher");

        queueDispatcher = getConnection().createDispatcher(msg -> {
        });

        queueDispatcher.subscribe(QUEUE_SUBJECT, QUEUE_GROUP, msg -> {
            try {
                String incomingMessage = new String(msg.getData());
                log.info("Received Request: " + incomingMessage);
                log.info("Message Reply To: " + msg.getReplyTo());
                String responseMessage = processMessage(incomingMessage);
                getConnection().publish(msg.getReplyTo(), responseMessage.getBytes());
            } catch (IOException ex) {
                log.error(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                log.error(ex.getMessage(), ex);
            }
        });
    }

    /**
     *
     * @throws IOException
     * @throws InterruptedException
     */
    private void createFNFDispatcher() throws IOException,
            InterruptedException {
        log.info("create FNF dispatcher");

        subscribeDispatcher = getConnection().createDispatcher(msg -> {
        });
        subscribeDispatcher.subscribe(FNF_SUBJECT, msg -> {
            lastFnFMessage = new String(msg.getData());
            log.info("FNF message:" + lastFnFMessage);

        });

    }

    private String processMessage(String incomingMessage) {
        String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, incomingMessage.toUpperCase());

        String processedMessage = null;

        switch (lastFnFMessage) {

            case CAPITALIZE:
                processedMessage = String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, incomingMessage.substring(0, 1).toUpperCase() + incomingMessage.substring(1));
                break;

            case LOWER_CASE:
                processedMessage = String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, incomingMessage.toLowerCase());
                break;
            case REVERSE:
                processedMessage = String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, new StringBuilder(incomingMessage).reverse().toString());
                break;
            case UPPER_CASE:
                processedMessage = String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, incomingMessage.toUpperCase());
                break;
            default:

                processedMessage = String.format(RESPONSE_TEMPLATE_STRING, this.queueWorkerName, incomingMessage.toUpperCase());

        }

        return processedMessage;
        
    }
    

    /**
     *
     */
    private void destroyDispatchers() {
        queueDispatcher.unsubscribe(QUEUE_SUBJECT);

        log.info("Unsubscribed");
    }

    /**
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @PostConstruct
    void postConstruct() throws IOException, InterruptedException {
        log.info(String.format("Queue Handler <%s>postCreate", this.queueWorkerName));
        createQueueWorkerDispatcher();
        createFNFDispatcher();

    }

    /**
     *
     */
    @PreDestroy
    public void preDestroy() {

        try {
            destroyDispatchers();

            connection.close();
        } catch (InterruptedException ex) {
            log.warn("unable to close connection.");
        }
    }

}
