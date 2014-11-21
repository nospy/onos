package org.onlab.onos.store.cluster.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verifyNotNull;
import static org.onlab.util.Tools.namedThreads;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.Leadership;
import org.onlab.onos.cluster.LeadershipEvent;
import org.onlab.onos.cluster.LeadershipEventListener;
import org.onlab.onos.cluster.LeadershipService;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.serializers.KryoNamespaces;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.onos.store.service.Lock;
import org.onlab.onos.store.service.LockService;
import org.onlab.onos.store.service.impl.DistributedLockManager;
import org.onlab.util.KryoNamespace;
import org.slf4j.Logger;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Distributed implementation of LeadershipService that is based on the primitives exposed by
 * LockService.
 */
@Component(immediate = true)
@Service
public class LeadershipManager implements LeadershipService {

    private final Logger log = getLogger(getClass());

    // TODO: Remove this dependency
    private static final int TERM_DURATION_MS =
            DistributedLockManager.DEAD_LOCK_TIMEOUT_MS;

    // Time to wait before retrying leadership after
    // a unexpected error.
    private static final int WAIT_BEFORE_RETRY_MS = 2000;

    // TODO: Appropriate Thread pool sizing.
    private static final ScheduledExecutorService THREAD_POOL =
            Executors.newScheduledThreadPool(25, namedThreads("leadership-manager-%d"));

    private static final MessageSubject LEADERSHIP_UPDATES =
            new MessageSubject("leadership-contest-updates");

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private LockService lockService;

    private final Map<String, Leadership> leaderBoard = Maps.newHashMap();

    private final Map<String, Lock> openContests = Maps.newHashMap();
    private final Set<LeadershipEventListener> listeners = Sets.newIdentityHashSet();
    private ControllerNode localNode;

    private final LeadershipEventListener peerAdvertiser = new PeerAdvertiser();
    private final LeadershipEventListener leaderBoardUpdater = new LeaderBoardUpdater();

    public static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoNamespace.newBuilder()
                    .register(KryoNamespaces.API)
                    .build()
                    .populate(1);
        }
    };

    @Activate
    public void activate() {
        localNode = clusterService.getLocalNode();

        addListener(peerAdvertiser);
        addListener(leaderBoardUpdater);

        clusterCommunicator.addSubscriber(
                LEADERSHIP_UPDATES,
                new PeerAdvertisementHandler());

        log.info("Started.");
    }

    @Deactivate
    public void deactivate() {
        removeListener(peerAdvertiser);
        removeListener(leaderBoardUpdater);

        clusterCommunicator.removeSubscriber(LEADERSHIP_UPDATES);

        THREAD_POOL.shutdown();

        log.info("Stopped.");
    }


    @Override
    public ControllerNode getLeader(String path) {
        synchronized (leaderBoard) {
            Leadership leadership = leaderBoard.get(path);
            if (leadership != null) {
                return leadership.leader();
            }
        }
        return null;
    }

    @Override
    public void runForLeadership(String path) {
        checkArgument(path != null);
        if (openContests.containsKey(path)) {
            log.info("Already in the leadership contest for {}", path);
            return;
        } else {
            Lock lock = lockService.create(path);
            openContests.put(path, lock);
            tryAcquireLeadership(path);
        }
    }

    @Override
    public void withdraw(String path) {
        checkArgument(path != null);
        Lock lock = openContests.remove(path);

        if (lock != null && lock.isLocked()) {
            lock.unlock();
            notifyListeners(
                    new LeadershipEvent(
                            LeadershipEvent.Type.LEADER_BOOTED,
                            new Leadership(lock.path(), localNode, lock.epoch())));
        }
    }

    @Override
    public void addListener(LeadershipEventListener listener) {
        checkArgument(listener != null);
        listeners.add(listener);
    }

    @Override
    public void removeListener(LeadershipEventListener listener) {
        checkArgument(listener != null);
        listeners.remove(listener);
    }

    private void notifyListeners(LeadershipEvent event) {
        for (LeadershipEventListener listener : listeners) {
            listener.event(event);
        }
    }

    private void tryAcquireLeadership(String path) {
        Lock lock = openContests.get(path);
        verifyNotNull(lock, "Lock should not be null");
        lock.lockAsync(TERM_DURATION_MS).whenComplete((response, error) -> {
            if (error == null) {
                THREAD_POOL.schedule(
                        new ReelectionTask(lock),
                        TERM_DURATION_MS / 2,
                        TimeUnit.MILLISECONDS);
                notifyListeners(
                        new LeadershipEvent(
                                LeadershipEvent.Type.LEADER_ELECTED,
                                new Leadership(lock.path(), localNode, lock.epoch())));
                return;
            } else {
                log.warn("Failed to acquire lock for {}. Will retry in {} sec", path, WAIT_BEFORE_RETRY_MS, error);
                try {
                    Thread.sleep(WAIT_BEFORE_RETRY_MS);
                    tryAcquireLeadership(path);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private class ReelectionTask implements Runnable {

        private final Lock lock;

        public ReelectionTask(Lock lock) {
           this.lock = lock;
        }

        @Override
        public void run() {
            if (lock.extendExpiration(TERM_DURATION_MS)) {
                notifyListeners(
                        new LeadershipEvent(
                                LeadershipEvent.Type.LEADER_REELECTED,
                                new Leadership(lock.path(), localNode, lock.epoch())));
                THREAD_POOL.schedule(this, TERM_DURATION_MS / 2, TimeUnit.MILLISECONDS);
            } else {
                if (openContests.containsKey(lock.path())) {
                    notifyListeners(
                            new LeadershipEvent(
                                    LeadershipEvent.Type.LEADER_BOOTED,
                                    new Leadership(lock.path(), localNode, lock.epoch())));
                    tryAcquireLeadership(lock.path());
                }
            }
        }
    }

    private class PeerAdvertiser implements LeadershipEventListener {
        @Override
        public void event(LeadershipEvent event) {
            // publish events originating on this host.
            if (event.subject().leader().equals(localNode)) {
                try {
                    clusterCommunicator.broadcast(
                            new ClusterMessage(
                                    localNode.id(),
                                    LEADERSHIP_UPDATES,
                                    SERIALIZER.encode(event)));
                } catch (IOException e) {
                    log.error("Failed to broadcast leadership update message", e);
                }
            }
        }
    }

    private class PeerAdvertisementHandler implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {
            LeadershipEvent event = SERIALIZER.decode(message.payload());
            log.debug("Received {} from {}", event, message.sender());
            notifyListeners(event);
        }
    }

    private class LeaderBoardUpdater implements LeadershipEventListener {
        @Override
        public void event(LeadershipEvent event) {
            Leadership leadershipUpdate = event.subject();
            synchronized (leaderBoard) {
                Leadership currentLeadership = leaderBoard.get(leadershipUpdate.topic());
                switch (event.type()) {
                case LEADER_ELECTED:
                case LEADER_REELECTED:
                        if (currentLeadership == null || currentLeadership.epoch() < leadershipUpdate.epoch()) {
                            leaderBoard.put(leadershipUpdate.topic(), leadershipUpdate);
                        }
                    break;
                case LEADER_BOOTED:
                    if (currentLeadership != null && currentLeadership.epoch() <= leadershipUpdate.epoch()) {
                        leaderBoard.remove(leadershipUpdate.topic());
                    }
                    break;
                default:
                    break;
                }
            }
        }
    }
}