/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.node.service;

import com.alipay.sofa.registry.common.model.ClientOffPublishers;
import com.alipay.sofa.registry.common.model.CommonResponse;
import com.alipay.sofa.registry.common.model.dataserver.*;
import com.alipay.sofa.registry.common.model.slot.Slot;
import com.alipay.sofa.registry.common.model.slot.SlotAccessGenericResponse;
import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.common.model.store.SubDatum;
import com.alipay.sofa.registry.common.model.store.URL;
import com.alipay.sofa.registry.common.model.store.UnPublisher;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.exchange.NodeExchanger;
import com.alipay.sofa.registry.remoting.exchange.RequestException;
import com.alipay.sofa.registry.remoting.exchange.message.Request;
import com.alipay.sofa.registry.remoting.exchange.message.Response;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.slot.SlotTableCache;
import com.alipay.sofa.registry.server.shared.env.ServerEnv;
import com.alipay.sofa.registry.server.shared.util.DatumUtils;
import com.alipay.sofa.registry.task.BlockingQueues;
import com.alipay.sofa.registry.task.FastRejectedExecutionException;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.StringFormatter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author shangyu.wh
 * @version $Id: DataNode.java, v 0.1 2017-12-01 11:30 shangyu.wh Exp $
 */
public class DataNodeServiceImpl implements DataNodeService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DataNodeServiceImpl.class);

  @Autowired private NodeExchanger dataNodeExchanger;

  @Autowired private SlotTableCache slotTableCache;

  @Autowired private SessionServerConfig sessionServerConfig;

  private Worker[] workers;
  private BlockingQueues<Req> blockingQueues;

  @PostConstruct
  public void init() {
    this.workers = new Worker[sessionServerConfig.getDataNodeExecutorWorkerSize()];
    blockingQueues =
        new BlockingQueues<>(
            sessionServerConfig.getDataNodeExecutorWorkerSize(),
            sessionServerConfig.getDataNodeExecutorQueueSize(),
            false);
    for (int i = 0; i < workers.length; i++) {
      workers[i] = new Worker(blockingQueues.getQueue(i));
      ConcurrentUtils.createDaemonThread("req-data-worker-" + i, workers[i]).start();
    }
  }

  private void commitReq(int slotId, Req req) {
    int idx = slotId % blockingQueues.queueNum();
    try {
      blockingQueues.put(idx, req);
    } catch (FastRejectedExecutionException e) {
      throw new FastRejectedExecutionException(
          String.format("commit req overflow, slotId=%d, %s", slotId, e.getMessage()));
    }
  }

  @Override
  public void register(final Publisher publisher) {
    final int slotId = slotTableCache.slotOf(publisher.getDataInfoId());
    commitReq(slotId, new Req(slotId, publisher));
  }

  @Override
  public void unregister(final Publisher publisher) {
    final int slotId = slotTableCache.slotOf(publisher.getDataInfoId());
    UnPublisher unPublisher = UnPublisher.of(publisher);
    commitReq(slotId, new Req(slotId, unPublisher));
  }

  @Override
  public void clientOff(ClientOffPublishers clientOffPublishers) {
    if (clientOffPublishers.isEmpty()) {
      return;
    }
    Map<Integer, ClientOffPublisher> groups = groupBySlot(clientOffPublishers);
    for (Map.Entry<Integer, ClientOffPublisher> group : groups.entrySet()) {
      final int slotId = group.getKey();
      final ClientOffPublisher clientOff = group.getValue();
      commitReq(slotId, new Req(slotId, clientOff));
    }
  }

  @Override
  public Map<String /*datainfoid*/, DatumVersion> fetchDataVersion(
      String dataCenter, int slotId, Map<String, DatumVersion> interests) {
    String dataNodeIp = null;
    try {
      final Slot slot = getSlot(slotId);
      dataNodeIp = slot.getLeader();
      final GetDataVersionRequest request =
          new GetDataVersionRequest(dataCenter, ServerEnv.PROCESS_ID, slotId, interests);
      request.setSlotTableEpoch(slotTableCache.getEpoch());
      request.setSlotLeaderEpoch(slot.getLeaderEpoch());
      Request<GetDataVersionRequest> getDataVersionRequestRequest =
          new Request<GetDataVersionRequest>() {
            @Override
            public GetDataVersionRequest getRequestBody() {
              return request;
            }

            @Override
            public URL getRequestUrl() {
              return getUrl(slot);
            }
          };

      Response response = dataNodeExchanger.request(getDataVersionRequestRequest);
      Object result = response.getResult();
      SlotAccessGenericResponse<Map<String, DatumVersion>> genericResponse =
          (SlotAccessGenericResponse<Map<String, DatumVersion>>) result;
      if (genericResponse.isSuccess()) {
        Map<String, DatumVersion> map = genericResponse.getData();
        return DatumUtils.intern(map);
      } else {
        throw new RuntimeException(
            StringFormatter.format(
                "GetDataVersion got fail response {}, {}, slotId={}, access={}, msg:{}",
                dataNodeIp,
                dataCenter,
                slotId,
                genericResponse.getSlotAccess(),
                genericResponse.getMessage()));
      }
    } catch (RequestException e) {
      throw new RuntimeException(
          StringFormatter.format(
              "GetDataVersion fail {}@{}, slotId={}", dataNodeIp, dataCenter, slotId, e));
    }
  }

  @Override
  public SubDatum fetch(String dataInfoId, String dataCenter) {
    String dataNodeIp = null;
    int slotId = -1;
    try {
      final Slot slot = getSlot(dataInfoId);
      dataNodeIp = slot.getLeader();
      slotId = slot.getId();
      GetDataRequest getDataRequest =
          new GetDataRequest(ServerEnv.PROCESS_ID, dataInfoId, dataCenter, slot.getId());
      getDataRequest.setSlotTableEpoch(slotTableCache.getEpoch());
      getDataRequest.setSlotLeaderEpoch(slot.getLeaderEpoch());
      Request<GetDataRequest> getDataRequestStringRequest =
          new Request<GetDataRequest>() {

            @Override
            public GetDataRequest getRequestBody() {
              return getDataRequest;
            }

            @Override
            public URL getRequestUrl() {
              return getUrl(slot);
            }

            @Override
            public Integer getTimeout() {
              return sessionServerConfig.getDataNodeExchangeForFetchDatumTimeoutMillis();
            }
          };

      Response response = dataNodeExchanger.request(getDataRequestStringRequest);
      Object result = response.getResult();
      SlotAccessGenericResponse<SubDatum> genericResponse =
          (SlotAccessGenericResponse<SubDatum>) result;
      if (genericResponse.isSuccess()) {
        final SubDatum datum = genericResponse.getData();
        return SubDatum.intern(datum);
      } else {
        throw new RuntimeException(
            StringFormatter.format(
                "GetData got fail response {}, {}, {}, slotId={} msg:{}",
                dataNodeIp,
                dataInfoId,
                dataCenter,
                slotId,
                genericResponse.getMessage()));
      }
    } catch (RequestException e) {
      throw new RuntimeException(
          StringFormatter.format(
              "GetData fail {}, {}, {}, slotId={}", dataNodeIp, dataInfoId, dataCenter, slotId),
          e);
    }
  }

  private CommonResponse sendRequest(Request request) throws RequestException {
    Response response = dataNodeExchanger.request(request);
    Object result = response.getResult();
    SlotAccessGenericResponse resp = (SlotAccessGenericResponse) result;
    if (!resp.isSuccess()) {
      throw new RuntimeException(
          String.format(
              "response failed, target: %s, request: %s, message: %s",
              request.getRequestUrl(), request.getRequestBody(), resp.getMessage()));
    }
    return resp;
  }

  private Slot getSlot(String dataInfoId) {
    final int slotId = slotTableCache.slotOf(dataInfoId);
    Slot slot = slotTableCache.getSlot(slotId);
    if (slot == null) {
      throw new RequestException(
          StringFormatter.format("slot not found for {}, slotId={}", dataInfoId, slotId));
    }
    return slot;
  }

  private Slot getSlot(int slotId) {
    Slot slot = slotTableCache.getSlot(slotId);
    if (slot == null) {
      throw new RequestException(StringFormatter.format("slot not found, slotId={}", slotId));
    }
    return slot;
  }

  private URL getUrl(Slot slot) {
    final String dataIp = slot.getLeader();
    if (StringUtils.isBlank(dataIp)) {
      throw new RequestException(String.format("slot has no leader, slotId=%s", slot));
    }
    return new URL(dataIp, sessionServerConfig.getDataServerPort());
  }

  private Map<Integer, ClientOffPublisher> groupBySlot(ClientOffPublishers clientOffPublishers) {
    List<Publisher> publishers = clientOffPublishers.getPublishers();
    Map<Integer, ClientOffPublisher> ret = Maps.newHashMap();
    for (Publisher publisher : publishers) {
      final String dataInfoId = publisher.getDataInfoId();
      int slotId = slotTableCache.slotOf(dataInfoId);
      ClientOffPublisher request =
          ret.computeIfAbsent(
              slotId, k -> new ClientOffPublisher(clientOffPublishers.getConnectId()));
      request.addPublisher(publisher);
    }
    return ret;
  }

  private static final class Req {
    final int slotId;
    final Object req;

    Req(int slotId, Object req) {
      this.slotId = slotId;
      this.req = req;
    }
  }

  private static final class RetryBatch {
    final BatchRequest batch;
    long expireTimestamp;
    int retryCount;

    RetryBatch(BatchRequest batch) {
      this.batch = batch;
    }

    @Override
    public String toString() {
      return "RetryBatch{"
          + "batch="
          + batch
          + ", retry="
          + retryCount
          + ", expire="
          + expireTimestamp
          + '}';
    }
  }

  private final class Worker implements Runnable {
    final BlockingQueue<Req> queue;
    final LinkedList<RetryBatch> retryBatches = Lists.newLinkedList();

    Worker(BlockingQueue<Req> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      for (; ; ) {
        try {
          final Req firstReq = queue.poll(200, TimeUnit.MILLISECONDS);
          if (firstReq != null) {
            // TODO config max
            Map<Integer, LinkedList<Object>> reqs =
                drainReq(queue, sessionServerConfig.getDataNodeMaxBatchSize());
            // send by order, firstReq.slotId is the first one
            LinkedList<Object> firstBatch = reqs.remove(firstReq.slotId);
            if (firstBatch == null) {
              firstBatch = Lists.newLinkedList();
            }
            firstBatch.addFirst(firstReq.req);
            request(firstReq.slotId, firstBatch);
            for (Map.Entry<Integer, LinkedList<Object>> batch : reqs.entrySet()) {
              request(batch.getKey(), batch.getValue());
            }
          }
          // check the retry
          if (!retryBatches.isEmpty()) {
            final Iterator<RetryBatch> it = retryBatches.iterator();
            List<RetryBatch> retries = Lists.newArrayList();
            while (it.hasNext()) {
              RetryBatch batch = it.next();
              it.remove();
              if (!DataNodeServiceImpl.this.request(batch.batch)) {
                retries.add(batch);
              }
            }
            for (RetryBatch retry : retries) {
              retry(retry);
            }
          }
        } catch (Throwable e) {
          LOGGER.error("failed to request batch", e);
        }
      }
    }

    private boolean retry(RetryBatch retry) {
      retry.retryCount++;
      if (retry.retryCount <= sessionServerConfig.getDataNodeRetryTimes()) {
        if (retryBatches.size() >= sessionServerConfig.getDataNodeRetryQueueSize()) {
          // remove the oldest
          retryBatches.removeFirst();
        }
        retry.expireTimestamp =
            System.currentTimeMillis() + sessionServerConfig.getDataNodeRetryBackoffMillis();
        retryBatches.add(retry);
        return true;
      }
      return false;
    }

    private boolean request(int slotId, List<Object> reqs) {
      final BatchRequest batch = new BatchRequest(ServerEnv.PROCESS_ID, slotId, reqs);
      if (!DataNodeServiceImpl.this.request(batch)) {
        retry(new RetryBatch(batch));
        return false;
      }
      return true;
    }
  }

  private boolean request(BatchRequest batch) {
    try {
      final Slot slot = getSlot(batch.getSlotId());
      batch.setSlotTableEpoch(slotTableCache.getEpoch());
      batch.setSlotLeaderEpoch(slot.getLeaderEpoch());
      sendRequest(
          new Request() {
            @Override
            public Object getRequestBody() {
              return batch;
            }

            @Override
            public URL getRequestUrl() {
              return getUrl(slot);
            }
          });
      return true;
    } catch (Throwable e) {
      LOGGER.error("failed to request batch, {}", batch, e);
      return false;
    }
  }

  private Map<Integer, LinkedList<Object>> drainReq(BlockingQueue<Req> queue, int max) {
    List<Req> reqs = new ArrayList<>(max);
    queue.drainTo(reqs, max);
    if (reqs.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<Integer, LinkedList<Object>> ret = Maps.newLinkedHashMap();
    for (Req req : reqs) {
      LinkedList<Object> objects = ret.computeIfAbsent(req.slotId, k -> Lists.newLinkedList());
      objects.add(req.req);
    }
    return ret;
  }
}
