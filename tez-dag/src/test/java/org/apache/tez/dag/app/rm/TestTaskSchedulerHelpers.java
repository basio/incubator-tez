/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.app.rm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.impl.AMRMClientImpl;
import org.apache.hadoop.yarn.event.Event;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.tez.dag.app.AppContext;
import org.apache.tez.dag.app.rm.TaskScheduler.ContainerSignatureMatcher;
import org.apache.tez.dag.app.rm.TaskScheduler.CookieContainerRequest;
import org.apache.tez.dag.app.rm.TaskScheduler.TaskSchedulerAppCallback;

import com.google.common.base.Preconditions;


class TestTaskSchedulerHelpers {

  // Mocking AMRMClientImpl to make use of getMatchingRequest
  static class AMRMClientForTest extends AMRMClientImpl<CookieContainerRequest> {

    @Override
    protected void serviceStart() {
    }

    @Override
    protected void serviceStop() {
    }
  }


  // Mocking AMRMClientAsyncImpl to make use of getMatchingRequest
  static class AMRMClientAsyncForTest extends
      TezAMRMClientAsync<CookieContainerRequest> {

    public AMRMClientAsyncForTest(
        AMRMClient<CookieContainerRequest> client,
        int intervalMs) {
      // CallbackHandler is not needed - will be called independently in the test.
      super(client, intervalMs, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RegisterApplicationMasterResponse registerApplicationMaster(
        String appHostName, int appHostPort, String appTrackingUrl) {
      RegisterApplicationMasterResponse mockRegResponse = mock(RegisterApplicationMasterResponse.class);
      Resource mockMaxResource = mock(Resource.class);
      Map<ApplicationAccessType, String> mockAcls = mock(Map.class);
      when(mockRegResponse.getMaximumResourceCapability()).thenReturn(
          mockMaxResource);
      when(mockRegResponse.getApplicationACLs()).thenReturn(mockAcls);
      return mockRegResponse;
    }

    @Override
    public void unregisterApplicationMaster(FinalApplicationStatus appStatus,
        String appMessage, String appTrackingUrl) {
    }

    @Override
    protected void serviceStart() {
    }

    @Override
    protected void serviceStop() {
    }
  }

  // Overrides start / stop. Will be controlled without the extra event handling thread.
  static class TaskSchedulerEventHandlerForTest extends
      TaskSchedulerEventHandler {

    private TezAMRMClientAsync<CookieContainerRequest> amrmClientAsync;
    private ContainerSignatureMatcher containerSignatureMatcher;

    @SuppressWarnings("rawtypes")
    public TaskSchedulerEventHandlerForTest(AppContext appContext,
        EventHandler eventHandler,
        TezAMRMClientAsync<CookieContainerRequest> amrmClientAsync,
        ContainerSignatureMatcher containerSignatureMatcher) {
      super(appContext, null, eventHandler);
      this.amrmClientAsync = amrmClientAsync;
      this.containerSignatureMatcher = containerSignatureMatcher;
    }

    @Override
    public TaskScheduler createTaskScheduler(String host, int port,
        String trackingUrl, AppContext appContext) {
      return new TaskSchedulerWithDrainableAppCallback(this,
          containerSignatureMatcher, host, port, trackingUrl, amrmClientAsync,
          appContext);
    }

    public TaskScheduler getSpyTaskScheduler() {
      return this.taskScheduler;
    }

    @Override
    public void serviceStart() {
      TaskScheduler taskSchedulerReal = createTaskScheduler("host", 0, "",
        appContext);
      // Init the service so that reuse configuration is picked up.
      taskSchedulerReal.serviceInit(getConfig());
      taskSchedulerReal.serviceStart();
      taskScheduler = spy(taskSchedulerReal);
    }

    @Override
    public void serviceStop() {
    }
  }

  @SuppressWarnings("rawtypes")
  static class CapturingEventHandler implements EventHandler {

    private List<Event> events = new LinkedList<Event>();


    public void handle(Event event) {
      events.add(event);
    }

    public void reset() {
      events.clear();
    }

    public void verifyNoInvocations(Class<? extends Event> eventClass) {
      for (Event e : events) {
        assertFalse(e.getClass().getName().equals(eventClass.getName()));
      }
    }

    public void verifyInvocation(Class<? extends Event> eventClass) {
      for (Event e : events) {
        if (e.getClass().getName().equals(eventClass.getName())) {
          return;
        }
      }
      fail("Expected Event: " + eventClass.getName() + " not sent");
    }
  }

  static class TaskSchedulerWithDrainableAppCallback extends TaskScheduler {

    private TaskSchedulerAppCallbackDrainable drainableAppCallback;

    public TaskSchedulerWithDrainableAppCallback(
        TaskSchedulerAppCallback appClient,
        ContainerSignatureMatcher containerSignatureMatcher,
        String appHostName, int appHostPort, String appTrackingUrl,
        AppContext appContext) {
      super(appClient, containerSignatureMatcher, appHostName, appHostPort,
          appTrackingUrl, appContext);
    }

    public TaskSchedulerWithDrainableAppCallback(
        TaskSchedulerAppCallback appClient,
        ContainerSignatureMatcher containerSignatureMatcher,
        String appHostName, int appHostPort, String appTrackingUrl,
        TezAMRMClientAsync<CookieContainerRequest> client,
        AppContext appContext) {
      super(appClient, containerSignatureMatcher, appHostName, appHostPort,
          appTrackingUrl, client, appContext);
    }

    @Override
    TaskSchedulerAppCallback createAppCallbackDelegate(
        TaskSchedulerAppCallback realAppClient) {
      drainableAppCallback = new TaskSchedulerAppCallbackDrainable(
          new TaskSchedulerAppCallbackWrapper(realAppClient,
              appCallbackExecutor));
      return drainableAppCallback;
    }

    public TaskSchedulerAppCallbackDrainable getDrainableAppCallback() {
      return drainableAppCallback;
    }
  }

  @SuppressWarnings("rawtypes")
  static class TaskSchedulerAppCallbackDrainable implements TaskSchedulerAppCallback {
    int completedEvents;
    int invocations;
    private TaskSchedulerAppCallback real;
    private CompletionService completionService;

    public TaskSchedulerAppCallbackDrainable(TaskSchedulerAppCallbackWrapper real) {
      completionService = real.completionService;
      this.real = real;
    }

    @Override
    public void taskAllocated(Object task, Object appCookie, Container container) {
      invocations++;
      real.taskAllocated(task, appCookie, container);
    }

    @Override
    public void containerCompleted(Object taskLastAllocated,
        ContainerStatus containerStatus) {
      invocations++;
      real.containerCompleted(taskLastAllocated, containerStatus);
    }

    @Override
    public void containerBeingReleased(ContainerId containerId) {
      invocations++;
      real.containerBeingReleased(containerId);
    }

    @Override
    public void nodesUpdated(List<NodeReport> updatedNodes) {
      invocations++;
      real.nodesUpdated(updatedNodes);
    }

    @Override
    public void appShutdownRequested() {
      invocations++;
      real.appShutdownRequested();
    }

    @Override
    public void setApplicationRegistrationData(Resource maxContainerCapability,
        Map<ApplicationAccessType, String> appAcls, ByteBuffer key) {
      invocations++;
      real.setApplicationRegistrationData(maxContainerCapability, appAcls, key);
    }

    @Override
    public void onError(Throwable t) {
      invocations++;
      real.onError(t);
    }

    @Override
    public float getProgress() {
      invocations++;
      return real.getProgress();
    }

    @Override
    public AppFinalStatus getFinalAppStatus() {
      invocations++;
      return real.getFinalAppStatus();
    }

    public void drain() throws InterruptedException, ExecutionException {
      while (completedEvents < invocations) {
        Future f = completionService.poll(5000l, TimeUnit.MILLISECONDS);
        if (f != null) {
          completedEvents++;
        } else {
          fail("Timed out while trying to drain queue");
        }

      }
    }
  }

  static class AlwaysMatchesContainerMatcher implements ContainerSignatureMatcher {

    @Override
    public boolean isSuperSet(Object cs1, Object cs2) {
      Preconditions.checkNotNull(cs1, "Arguments cannot be null");
      Preconditions.checkNotNull(cs2, "Arguments cannot be null");
      return true;
    }

    @Override
    public boolean isExactMatch(Object cs1, Object cs2) {
      return true;
    }
  }
  
  static class PreemptionMatcher implements ContainerSignatureMatcher {
    @Override
    public boolean isSuperSet(Object cs1, Object cs2) {
      Preconditions.checkNotNull(cs1, "Arguments cannot be null");
      Preconditions.checkNotNull(cs2, "Arguments cannot be null");
      return true;
    }

    @Override
    public boolean isExactMatch(Object cs1, Object cs2) {
      if (cs1 == cs2 && cs1 != null) {
        return true;
      }
      return false;
    }
  }
  

  static void waitForDelayedDrainNotify(AtomicBoolean drainNotifier)
      throws InterruptedException {
    while (!drainNotifier.get()) {
      synchronized (drainNotifier) {
        drainNotifier.wait();
      }
    }
  }

}
