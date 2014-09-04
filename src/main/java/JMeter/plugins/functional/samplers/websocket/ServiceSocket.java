/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package JMeter.plugins.functional.samplers.websocket;

import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.log.Logger;
import java.util.regex.Pattern;
import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jorphan.logging.LoggingManager;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 *
 * @author Maciej Zaleski
 */
@WebSocket(maxTextMessageSize = 256 * 1024 * 1024)
public class ServiceSocket {

    protected WebSocketSampler sampler;
    protected WebSocketClient client;
    private static final Logger log = LoggingManager.getLoggerForClass();
    protected Deque<String> responeBacklog = new LinkedList<>();
    protected Integer error = 0;
    protected StringBuffer logMessage = new StringBuffer();
    protected CountDownLatch openLatch = new CountDownLatch(1);
    protected CountDownLatch closeLatch = new CountDownLatch(1);
    protected Session session = null;
    protected int messageCounter = 1;
    protected Pattern responseExpression;
    protected Pattern disconnectExpression;
    protected boolean connected = false;

    public ServiceSocket(WebSocketSampler sampler, WebSocketClient client) {
        this.sampler = sampler;
        this.client = client;
        
        logMessage.append("\n\n[Execution Flow]\n");
        logMessage.append(" - Opening new connection\n");
        initializePatterns();
    }

    @OnWebSocketMessage
    public void onMessage(String msg) {
        log.debug("Received message: " + msg);
        String length = " (" + msg.length() + " bytes)";
        logMessage.append(" - Received message #").append(messageCounter).append(length);
        addResponseMessage("[Message " + (messageCounter++) + "]\n" + msg + "\n\n");

        if (responseExpression == null || responseExpression.matcher(msg).find()) {
            logMessage.append("; matched response pattern").append("\n");
            closeLatch.countDown();
        } else if (disconnectExpression != null && disconnectExpression.matcher(msg).find()) {
            logMessage.append("; matched connection close pattern").append("\n");
            closeLatch.countDown();
            close(StatusCode.NORMAL, "JMeter closed session.");
        } else {
            logMessage.append("; didn't match any pattern").append("\n");
        }
    }

    @OnWebSocketConnect
    public void onOpen(Session session) {
        logMessage.append(" - WebSocket conection has been opened").append("\n");
        log.debug("Connect " + session.isOpen());
        this.session = session;
        connected = true;
        openLatch.countDown();
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason) {
        if (statusCode != 1000) {
            log.error("Disconnect " + statusCode + ": " + reason);
            logMessage.append(" - WebSocket conection closed unexpectedly by the server: [").append(statusCode).append("] ").append(reason).append("\n");
            error = statusCode;
        } else {
            logMessage.append(" - WebSocket conection has been successfully closed by the server").append("\n");
            log.debug("Disconnect " + statusCode + ": " + reason);
        }
        
        //Notify connection opening and closing latches of the closed connection
        openLatch.countDown();
        closeLatch.countDown();
        connected = false;
    }

    /**
     * @return response message made of messages saved in the responeBacklog cache
     */
    public String getResponseMessage() {
        String responseMessage = "";
        
        //Iterate through response messages saved in the responeBacklog cache
        for (String aResponseBacklog : responeBacklog) {
            responseMessage += aResponseBacklog;
        }

        return responseMessage;
    }

    public void clearBacklog() {
        responeBacklog.clear();
    }

    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for messages for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.closeLatch.await(duration, unit);

        if (!sampler.isStreamingConnection()) {
            close(StatusCode.NORMAL, "JMeter closed session.");
        } else {
            logMessage.append(" - Leaving streaming connection open").append("\n");
        }

        return res;
    }

    public boolean awaitOpen(int duration, TimeUnit unit) throws InterruptedException {
        logMessage.append(" - Waiting for the server connection for ").append(duration).append(" ").append(unit.toString()).append("\n");
        boolean res = this.openLatch.await(duration, unit);

        if (connected) {
            logMessage.append(" - Connection established").append("\n");
        } else {
            logMessage.append(" - Cannot connect to the remote server").append("\n");
        }

        return res;
    }

    /**
     * @return the session
     */
    public Session getSession() {
        return session;
    }

    public void sendMessage(String message) throws IOException {
        session.getRemote().sendString(message);
    }

    public void close() {
        close(StatusCode.NORMAL, "JMeter closed session.");
    }

    public void close(int statusCode, String statusText) {
        //Closing WebSocket session
        if (session != null) {
            session.close(statusCode, statusText);
            logMessage.append(" - WebSocket session closed by the client").append("\n");
        } else {
            logMessage.append(" - WebSocket session wasn't started (...that's odd)").append("\n");
        }
        
        
        //Stoping WebSocket client; thanks m0ro
        try {
            client.stop();
            logMessage.append(" - WebSocket client closed by the client").append("\n");
        } catch (Exception e) {
            logMessage.append(" - WebSocket client wasn't started (...that's odd)").append("\n");
        }
    }

    /**
     * @return the error
     */
    public Integer getError() {
        return error;
    }

    /**
     * @return the logMessage
     */
    public String getLogMessage() {
        logMessage.append("\n\n[Variables]\n");
        logMessage.append(" - Message count: ").append(messageCounter - 1).append("\n");

        return logMessage.toString();
    }

    protected void initializePatterns() {
        //Evaluate response matching patterns in case thay contain JMeter variables (i.e. ${var})
        String responsePattern = new CompoundVariable(sampler.getResponsePattern()).execute();
        String disconnectPattern = new CompoundVariable(sampler.getCloseConncectionPattern()).execute();

        try {
            logMessage.append(" - Using response message pattern \"").append(responsePattern).append("\"\n");
            responseExpression = (responsePattern != null && !responsePattern.isEmpty()) ? Pattern.compile(responsePattern) : null;
        } catch (Exception ex) {
            logMessage.append(" - Invalid response message regular expression pattern: ").append(ex.getLocalizedMessage()).append("\n");
            log.error("Invalid response message regular expression pattern: " + ex.getLocalizedMessage());
            responseExpression = null;
        }

        try {
            logMessage.append(" - Using disconnect pattern \"").append(disconnectPattern).append("\"\n");
            disconnectExpression = (disconnectPattern != null && !disconnectPattern.isEmpty()) ? Pattern.compile(disconnectPattern) : null;
        } catch (Exception ex) {
            logMessage.append(" - Invalid disconnect regular expression pattern: ").append(ex.getLocalizedMessage()).append("\n");
            log.error("Invalid disconnect regular regular expression pattern: " + ex.getLocalizedMessage());
            disconnectExpression = null;
        }

    }

    /**
     * @return the connected
     */
    public boolean isConnected() {
        return connected;
    }

    public void initialize(WebSocketSampler sampler) {
        this.sampler = sampler;

        logMessage = new StringBuffer();
        logMessage.append("\n\n[Execution Flow]\n");
        logMessage.append(" - Reusing exising connection\n");
        error = 0;

        initializePatterns();

        this.closeLatch = new CountDownLatch(1);
    }

    private void addResponseMessage(String message) {
        int messageBacklog;
        try {
            messageBacklog = Integer.parseInt(sampler.getMessageBacklog());
        } catch (Exception ex) {
            logMessage.append(" - Message backlog value not set; using default ").append(WebSocketSampler.MESSAGE_BACKLOG_COUNT).append("\n");
            messageBacklog = WebSocketSampler.MESSAGE_BACKLOG_COUNT;
        }

        while (responeBacklog.size() >= messageBacklog) {
            responeBacklog.poll();
        }
        responeBacklog.add(message);
    }
}
