package org.openhab.binding.devireg.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.opensdg.protocol.DeviSmart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeerConnectionHandler {

    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private ISDGPeerHandler thingHandler;
    private Logger logger;
    private byte[] peerId;
    private DeviSmartConnection connection;
    private @Nullable Future<?> reconnectReq;

    PeerConnectionHandler(ISDGPeerHandler handler) {
        this.thingHandler = handler;
        logger = LoggerFactory.getLogger(handler.getClass());
    }

    public void initialize(DeviRegConfiguration config) {
        logger.trace("initialize()");

        DanfossGridConnection.AddUser();

        peerId = SDGUtils.ParseKey(config.peerId);
        if (peerId == null) {
            logger.error("Peer ID is not set");
            thingHandler.reportStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "Peer ID is not set");
            return;
        }

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        thingHandler.reportStatus(ThingStatus.UNKNOWN);

        connection = new DeviSmartConnection(this);
        connect();
    }

    public void dispose() {
        logger.trace("dispose()");

        singleThread.execute(() -> {
            DeviSmartConnection conn = connection;
            connection = null;

            if (reconnectReq != null) {
                reconnectReq.cancel(false);
                reconnectReq = null;
            }

            if (conn != null) {
                conn.SetBlockingMode(true);
                conn.Close();
                logger.info("Connection closed");
                conn.Dispose();
            }
        });

        DanfossGridConnection.RemoveUser();
    }

    private void connect() {
        // In order not to mess up our connection state we need to make sure
        // that any two calls are never running concurrently. We use
        // singleThreadExecutorService for this purpose
        singleThread.execute(() -> {
            if (connection == null) {
                return; // Stale Reconnect request from deleted/disabled Thing
            }

            DanfossGridConnection grid = DanfossGridConnection.get();

            if (grid == null) {
                setOfflineStatus("Danfoss grid connection failed");
            } else {
                logger.info("Connecting to peer " + DatatypeConverter.printHexBinary(peerId));
                connection.ConnectToRemote(grid, peerId, DeviSmart.ProtocolName);
            }
        });
    }

    public void setOnlineStatus() {
        logger.info("Connection established");
        thingHandler.reportStatus(ThingStatus.ONLINE);
    }

    public void setOfflineStatus(String reason) {
        logger.error(reason);
        thingHandler.reportStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, reason);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        reconnectReq = thingHandler.getScheduler().schedule(() -> {
            connect();
        }, 10, TimeUnit.SECONDS);
    }

    public void Send(byte[] data) {
        if (connection != null) {
            connection.Send(data);
        }
    }

    public void SendPacket(DeviSmart.Packet pkt) {
        if (connection != null) {
            connection.Send(pkt.getBuffer());
        }
    }

    public void handlePacket(DeviSmart.Packet pkt) {
        thingHandler.handlePacket(pkt);
    }
}