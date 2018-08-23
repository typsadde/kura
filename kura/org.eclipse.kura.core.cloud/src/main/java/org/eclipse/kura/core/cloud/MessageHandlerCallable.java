/*******************************************************************************
 * Copyright (c) 2018 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.kura.core.cloud;

import static org.eclipse.kura.cloudconnection.request.RequestHandlerConstants.ARGS_KEY;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.publisher.CloudNotificationPublisher;
import org.eclipse.kura.cloudconnection.request.RequestHandler;
import org.eclipse.kura.cloudconnection.request.RequestHandlerContext;
import org.eclipse.kura.data.DataService;
import org.eclipse.kura.message.KuraPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageHandlerCallable implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(MessageHandlerCallable.class);

    private static final Pattern RESOURCES_DELIM = Pattern.compile("/");

    public static final String METRIC_REQUEST_ID = "request.id";
    public static final String REQUESTER_CLIENT_ID = "requester.client.id";

    public static final String METRIC_RESPONSE_CODE = "response.code";
    public static final String METRIC_EXCEPTION_MSG = "response.exception.message";
    public static final String METRIC_EXCEPTION_STACK = "response.exception.stack";

    public static final int RESPONSE_CODE_OK = 200;
    public static final int RESPONSE_CODE_BAD_REQUEST = 400;
    public static final int RESPONSE_CODE_NOTFOUND = 404;
    public static final int RESPONSE_CODE_ERROR = 500;

    protected static final int DFLT_PUB_QOS = 0;
    protected static final boolean DFLT_RETAIN = false;
    protected static final int DFLT_PRIORITY = 1;

    private final RequestHandler cloudApp;
    private final String appId;
    private final String appTopic;
    private final KuraPayload kuraMessage;
    private final CloudServiceImpl cloudService;

    public MessageHandlerCallable(RequestHandler cloudApp, String appId, String appTopic, KuraPayload msg,
            CloudServiceImpl cloudService) {
        super();
        this.cloudApp = cloudApp;
        this.appId = appId;
        this.appTopic = appTopic;
        this.kuraMessage = msg;
        this.cloudService = cloudService;
    }

    @Override
    public Void call() throws Exception {
        logger.debug("Control Arrived on topic: {}", this.appTopic);

        String requestId = (String) this.kuraMessage.getMetric(METRIC_REQUEST_ID);
        String requesterClientId = (String) this.kuraMessage.getMetric(REQUESTER_CLIENT_ID);
        if (requestId == null || requesterClientId == null) {
            throw new ParseException("Not a valid request payload", 0);
        }

        // Prepare the default response
        KuraPayload reqPayload = this.kuraMessage;
        KuraMessage response;

        String notificationPublisherPid = this.cloudService.getNotificationPublisherPid();
        CloudNotificationPublisher notificationPublisher = this.cloudService.getNotificationPublisher();
        RequestHandlerContext requestContext = new RequestHandlerContext(notificationPublisherPid,
                notificationPublisher);

        try {
            Iterator<String> resources = RESOURCES_DELIM.splitAsStream(this.appTopic).iterator();

            if (!resources.hasNext()) {
                throw new IllegalArgumentException();
            }

            String method = resources.next();

            Map<String, Object> reqResources = getMessageResources(resources);

            KuraMessage reqMessage = new KuraMessage(reqPayload, reqResources);

            switch (method) {
            case "GET":
                logger.debug("Handling GET request topic: {}", this.appTopic);
                response = this.cloudApp.doGet(requestContext, reqMessage);
                break;

            case "PUT":
                logger.debug("Handling PUT request topic: {}", this.appTopic);
                response = this.cloudApp.doPut(requestContext, reqMessage);
                break;

            case "POST":
                logger.debug("Handling POST request topic: {}", this.appTopic);
                response = this.cloudApp.doPost(requestContext, reqMessage);
                break;

            case "DEL":
                logger.debug("Handling DEL request topic: {}", this.appTopic);
                response = this.cloudApp.doDel(requestContext, reqMessage);
                break;

            case "EXEC":
                logger.debug("Handling EXEC request topic: {}", this.appTopic);
                response = this.cloudApp.doExec(requestContext, reqMessage);
                break;

            default:
                logger.error("Bad request topic: {}", this.appTopic);
                KuraPayload payload = new KuraPayload();
                response = setResponseCode(payload, RESPONSE_CODE_BAD_REQUEST);
                break;
            }
        } catch (IllegalArgumentException e) {
            logger.error("Bad request topic: {}", this.appTopic);
            KuraPayload payload = new KuraPayload();
            response = setResponseCode(payload, RESPONSE_CODE_BAD_REQUEST);
        } catch (KuraException e) {
            logger.error("Error handling request topic: {}", this.appTopic, e);
            response = manageException(e);
        }

        buildResponseMessage(requestId, requesterClientId, response);

        return null;
    }

    private void buildResponseMessage(String requestId, String requesterClientId, KuraMessage response) {
        try {
            response.getPayload().setTimestamp(new Date());

            StringBuilder sb = new StringBuilder("REPLY").append("/").append(requestId);
            logger.debug("Publishing response topic: {}", sb);

            DataService dataService = this.cloudService.getDataService();
            String fullTopic = encodeTopic(requesterClientId, sb.toString());
            byte[] appPayload = this.cloudService.encodePayload(response.getPayload());
            dataService.publish(fullTopic, appPayload, DFLT_PUB_QOS, DFLT_RETAIN, DFLT_PRIORITY);
        } catch (KuraException e) {
            logger.error("Error publishing response for topic: {}\n{}", this.appTopic, e);
        }
    }

    private KuraMessage manageException(KuraException e) {
        KuraMessage message;
        KuraPayload payload = new KuraPayload();
        setException(payload, e);
        if (e.getCode().equals(KuraErrorCode.BAD_REQUEST)) {
            message = setResponseCode(payload, RESPONSE_CODE_BAD_REQUEST);
        } else if (e.getCode().equals(KuraErrorCode.NOT_FOUND)) {
            message = setResponseCode(payload, RESPONSE_CODE_NOTFOUND);
        } else {
            message = setResponseCode(payload, RESPONSE_CODE_ERROR);
        }
        return message;
    }

    private Map<String, Object> getMessageResources(Iterator<String> iter) {
        List<String> resourcesList = new ArrayList<>();
        while (iter.hasNext()) {
            resourcesList.add(iter.next());
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(ARGS_KEY.value(), resourcesList);
        return properties;
    }

    public KuraMessage setResponseCode(KuraPayload payload, int responseCode) {
        payload.addMetric(METRIC_RESPONSE_CODE, Integer.valueOf(responseCode));
        return new KuraMessage(payload);
    }

    public void setException(KuraPayload payload, Throwable t) {
        if (t != null) {
            payload.addMetric(METRIC_EXCEPTION_MSG, t.getMessage());
            payload.addMetric(METRIC_EXCEPTION_STACK, stackTraceAsString(t));
        }
    }

    private String stackTraceAsString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private String encodeTopic(String deviceId, String appTopic) {
        CloudServiceOptions options = this.cloudService.getCloudServiceOptions();
        StringBuilder sb = new StringBuilder();
        sb.append(options.getTopicControlPrefix()).append(options.getTopicSeparator());

        sb.append(options.getTopicAccountToken()).append(options.getTopicSeparator()).append(deviceId)
                .append(options.getTopicSeparator()).append(this.appId);

        if (appTopic != null && !appTopic.isEmpty()) {
            sb.append(options.getTopicSeparator()).append(appTopic);
        }

        return sb.toString();
    }
}
