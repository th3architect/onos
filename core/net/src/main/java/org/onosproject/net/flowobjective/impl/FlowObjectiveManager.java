/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.net.flowobjective.impl;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.osgi.DefaultServiceDirectory;
import org.onlab.osgi.ServiceDirectory;
import org.onlab.util.ItemNotFoundException;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.NodeId;
import org.onosproject.mastership.MastershipEvent;
import org.onosproject.mastership.MastershipListener;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.FlowObjectiveStoreDelegate;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.flowobjective.ObjectiveEvent;
import org.onosproject.net.group.GroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.onlab.util.Tools.groupedThreads;

/**
 * Provides implementation of the flow objective programming service.
 */
@Component(immediate = true)
@Service
public class FlowObjectiveManager implements FlowObjectiveService {

    public static final int INSTALL_RETRY_ATTEMPTS = 5;
    public static final long INSTALL_RETRY_INTERVAL = 1000; // ms

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DriverService driverService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    // Note: The following dependencies are added on behalf of the pipeline
    // driver behaviours to assure these services are available for their
    // initialization.
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveStore flowObjectiveStore;

    private final FlowObjectiveStoreDelegate delegate = new InternalStoreDelegate();

    private final Map<DeviceId, DriverHandler> driverHandlers = Maps.newConcurrentMap();
    private final Map<DeviceId, Pipeliner> pipeliners = Maps.newConcurrentMap();

    private final PipelinerContext context = new InnerPipelineContext();
    private final MastershipListener mastershipListener = new InnerMastershipListener();
    private final DeviceListener deviceListener = new InnerDeviceListener();

    protected ServiceDirectory serviceDirectory = new DefaultServiceDirectory();

    private NodeId localNode;

    private Map<Integer, Set<PendingNext>> pendingForwards =
            Maps.newConcurrentMap();

    private ExecutorService executorService;

    @Activate
    protected void activate() {
        executorService = Executors.newFixedThreadPool(
                4, groupedThreads("onos/objective-installer", "%d"));

        flowObjectiveStore.setDelegate(delegate);
        localNode = clusterService.getLocalNode().id();
        mastershipService.addListener(mastershipListener);
        deviceService.addListener(deviceListener);
        deviceService.getDevices().forEach(device -> setupPipelineHandler(device.id()));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        flowObjectiveStore.unsetDelegate(delegate);
        mastershipService.removeListener(mastershipListener);
        deviceService.removeListener(deviceListener);
        log.info("Stopped");
    }

    /**
     * Task that passes the flow objective down to the driver. The task will
     * make a few attempts to find the appropriate driver, then eventually give
     * up and report an error if no suitable driver could be found.
     */
    private class ObjectiveInstaller implements Runnable {
        private final DeviceId deviceId;
        private final Objective objective;

        private int numAttempts = 0;

        public ObjectiveInstaller(DeviceId deviceId, Objective objective) {
            this.deviceId = deviceId;
            this.objective = objective;
        }

        @Override
        public void run() {
            try {
                numAttempts++;

                Pipeliner pipeliner = getDevicePipeliner(deviceId);

                if (pipeliner != null) {
                    if (objective instanceof NextObjective) {
                        pipeliner.next((NextObjective) objective);
                    } else if (objective instanceof ForwardingObjective) {
                        pipeliner.forward((ForwardingObjective) objective);
                    } else {
                        pipeliner.filter((FilteringObjective) objective);
                    }
                } else if (numAttempts < INSTALL_RETRY_ATTEMPTS) {
                    Thread.currentThread().sleep(INSTALL_RETRY_INTERVAL);
                    executorService.submit(this);
                } else {
                    // Otherwise we've tried a few times and failed, report an
                    // error back to the user.
                    objective.context().ifPresent(
                            c -> c.onError(objective, ObjectiveError.DEVICEMISSING));
                }
            } catch (Exception e) {
                log.warn("Exception while installing flow objective", e);
            }
        }
    }

    @Override
    public void filter(DeviceId deviceId, FilteringObjective filteringObjective) {
        executorService.submit(new ObjectiveInstaller(deviceId, filteringObjective));
    }

    @Override
    public void forward(DeviceId deviceId,
                                   ForwardingObjective forwardingObjective) {

        if (queueObjective(deviceId, forwardingObjective)) {
            return;
        }

        executorService.submit(new ObjectiveInstaller(deviceId, forwardingObjective));
    }

    @Override
    public void next(DeviceId deviceId, NextObjective nextObjective) {
        executorService.submit(new ObjectiveInstaller(deviceId, nextObjective));
    }

    @Override
    public int allocateNextId() {
        return flowObjectiveStore.allocateNextId();
    }

    private boolean queueObjective(DeviceId deviceId, ForwardingObjective fwd) {
        if (fwd.nextId() != null &&
                flowObjectiveStore.getNextGroup(fwd.nextId()) == null) {
            log.trace("Queuing forwarding objective for nextId {}", fwd.nextId());
            if (pendingForwards.putIfAbsent(fwd.nextId(),
                                Sets.newHashSet(new PendingNext(deviceId, fwd))) != null) {
                Set<PendingNext> pending = pendingForwards.get(fwd.nextId());
                pending.add(new PendingNext(deviceId, fwd));
            }
            return true;
        }
        return false;
    }

    // Retrieves the device pipeline behaviour from the cache.
    private Pipeliner getDevicePipeliner(DeviceId deviceId) {
        Pipeliner pipeliner = pipeliners.get(deviceId);
        return pipeliner;
    }

    private void setupPipelineHandler(DeviceId deviceId) {
        // Attempt to lookup the handler in the cache
        DriverHandler handler = driverHandlers.get(deviceId);
        if (handler == null) {
            try {
                // Otherwise create it and if it has pipeline behaviour, cache it
                handler = driverService.createHandler(deviceId);
                if (!handler.driver().hasBehaviour(Pipeliner.class)) {
                    log.warn("Pipeline behaviour not supported for device {}",
                             deviceId);
                    return;
                }
            } catch (ItemNotFoundException e) {
                log.warn("No applicable driver for device {}", deviceId);
                return;
            }

            driverHandlers.put(deviceId, handler);
        }

        // Always (re)initialize the pipeline behaviour
        log.info("Driver {} bound to device {} ... initializing driver",
                 handler.driver().name(), deviceId);
        Pipeliner pipeliner = handler.behaviour(Pipeliner.class);
        pipeliner.init(deviceId, context);
        pipeliners.putIfAbsent(deviceId, pipeliner);
    }

    // Triggers driver setup when the local node becomes a device master.
    private class InnerMastershipListener implements MastershipListener {
        @Override
        public void event(MastershipEvent event) {
            switch (event.type()) {
                case MASTER_CHANGED:
                    log.info("mastership changed on device {}", event.subject());
                    setupPipelineHandler(event.subject());
                    break;
                case BACKUPS_CHANGED:
                    break;
                default:
                    break;
            }
        }
    }

    // Triggers driver setup when a device is (re)detected.
    private class InnerDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_ADDED:
                    setupPipelineHandler(event.subject().id());
                    break;
                case DEVICE_AVAILABILITY_CHANGED:
                    log.info("Device either added or availability changed {}",
                             event.subject().id());
                    if (deviceService.isAvailable(event.subject().id())) {
                        log.info("Device is now available {}", event.subject().id());
                        setupPipelineHandler(event.subject().id());
                    }
                    break;
                case DEVICE_UPDATED:
                    break;
                case DEVICE_REMOVED:
                    break;
                case DEVICE_SUSPENDED:
                    break;
                case PORT_ADDED:
                    break;
                case PORT_UPDATED:
                    break;
                case PORT_REMOVED:
                    break;
                default:
                    break;
            }
        }
    }

    // Processing context for initializing pipeline driver behaviours.
    private class InnerPipelineContext implements PipelinerContext {
        @Override
        public ServiceDirectory directory() {
            return serviceDirectory;
        }

        @Override
        public FlowObjectiveStore store() {
            return flowObjectiveStore;
        }
    }

    private class InternalStoreDelegate implements FlowObjectiveStoreDelegate {
        @Override
        public void notify(ObjectiveEvent event) {
            log.debug("Received notification of obj event {}", event);
            Set<PendingNext> pending = pendingForwards.remove(event.subject());

            if (pending == null) {
                log.debug("Nothing pending for this obj event");
                return;
            }

            log.debug("Processing pending forwarding objectives {}", pending.size());

            pending.forEach(p -> getDevicePipeliner(p.deviceId())
                    .forward(p.forwardingObjective()));

        }
    }

    /**
     * Data class used to hold a pending forwarding objective that could not
     * be processed because the associated next object was not present.
     */
    private class PendingNext {
        private final DeviceId deviceId;
        private final ForwardingObjective fwd;

        public PendingNext(DeviceId deviceId, ForwardingObjective fwd) {
            this.deviceId = deviceId;
            this.fwd = fwd;
        }

        public DeviceId deviceId() {
            return deviceId;
        }

        public ForwardingObjective forwardingObjective() {
            return fwd;
        }
    }
}
