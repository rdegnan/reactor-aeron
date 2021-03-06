package reactor.ipc.aeron;

import io.aeron.Publication;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * @author Anatoly Kadyshev
 */
public class HeartbeatSender {

    private final Logger logger;

    private final long heartbeatIntervalMillis;

    private final Scheduler scheduler;

    public HeartbeatSender(long heartbeatIntervalMillis, String category) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.logger = Loggers.getLogger(HeartbeatSender.class + "." + category);
        this.scheduler = Schedulers.single();
    }

    public Mono<Void> scheduleHeartbeats(Publication controlPub, long sessionId) {
        return Mono.create(sink -> {
            Disposable disposable = scheduler.schedulePeriodically(new SendHeartbeatTask(sink, controlPub, sessionId),
                    heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
            sink.onDispose(disposable);
        });
    }

    class SendHeartbeatTask implements Runnable {

        private final MonoSink<Void> sink;

        private final Publication controlPub;

        private final long sessionId;

        private boolean isFailed = false;

        private int failCounter = 0;

        SendHeartbeatTask(MonoSink<Void> sink, Publication controlPub, long sessionId) {
            this.sink = sink;
            this.controlPub = controlPub;
            this.sessionId = sessionId;
        }

        @Override
        public void run() {
            if (isFailed) {
                return;
            }

            ByteBuffer buffer = Protocol.createHeartbeatBody(sessionId);
            MessagePublisher publisher = new MessagePublisher(logger, 0, 0);
            Exception cause = null;
            long result = 0;
            try {
                result = publisher.publish(controlPub, MessageType.HEARTBEAT, buffer, sessionId);
                if (result > 0) {
                    failCounter = 0;
                    logger.debug("Sent heartbeat for session with Id: {}", sessionId);
                    return;
                } else if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                    failCounter++;
                    if (failCounter < 2) {
                        return;
                    }
                }
            } catch (Exception ex) {
                cause = ex;
            }
            isFailed = true;
            logger.debug("Failed to send heartbeat for session with Id: {}, result: {}", sessionId, result, cause);
            sink.error(new HeartbeatSendFailedException(sessionId));
        }

    }

}
