/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.metadata;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.internal.core.adminrequest.AdminRequestHandler;
import com.datastax.oss.driver.internal.core.adminrequest.AdminResult;
import com.datastax.oss.driver.internal.core.adminrequest.AdminRow;
import com.datastax.oss.driver.internal.core.adminrequest.UnexpectedResponseException;
import com.datastax.oss.driver.internal.core.channel.DriverChannel;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.control.ControlConnection;
import com.datastax.oss.driver.internal.core.util.concurrent.CompletableFutures;
import com.datastax.oss.driver.shaded.guava.common.annotations.VisibleForTesting;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.response.Error;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.jcip.annotations.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default topology monitor, based on {@link ControlConnection}.
 *
 * <p>Note that event processing is implemented directly in the control connection, not here.
 */
@ThreadSafe
public class DefaultTopologyMonitor implements TopologyMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultTopologyMonitor.class);

  // Assume topology queries never need paging
  private static final int INFINITE_PAGE_SIZE = -1;

  private final String logPrefix;
  private final InternalDriverContext context;
  private final ControlConnection controlConnection;
  private final Duration timeout;
  private final boolean reconnectOnInit;
  private final CompletableFuture<Void> closeFuture;

  @VisibleForTesting volatile boolean isSchemaV2;
  @VisibleForTesting volatile int port = -1;

  public DefaultTopologyMonitor(InternalDriverContext context) {
    this.logPrefix = context.getSessionName();
    this.context = context;
    this.controlConnection = context.getControlConnection();
    DriverExecutionProfile config = context.getConfig().getDefaultProfile();
    this.timeout = config.getDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT);
    this.reconnectOnInit = config.getBoolean(DefaultDriverOption.RECONNECT_ON_INIT);
    this.closeFuture = new CompletableFuture<>();
    // Set this to true initially, after the first refreshNodes is called this will either stay true
    // or be set to false;
    this.isSchemaV2 = true;
  }

  @Override
  public CompletionStage<Void> init() {
    if (closeFuture.isDone()) {
      return CompletableFutures.failedFuture(new IllegalStateException("closed"));
    }
    return controlConnection.init(true, reconnectOnInit, true);
  }

  @Override
  public CompletionStage<Void> initFuture() {
    return controlConnection.initFuture();
  }

  @Override
  public CompletionStage<Optional<NodeInfo>> refreshNode(Node node) {
    if (closeFuture.isDone()) {
      return CompletableFutures.failedFuture(new IllegalStateException("closed"));
    }
    LOG.debug("[{}] Refreshing info for {}", logPrefix, node);
    DriverChannel channel = controlConnection.channel();
    EndPoint localEndPoint = channel.getEndPoint();
    if (node.getEndPoint().equals(channel.getEndPoint())) {
      // refreshNode is called for nodes that just came up. If the control node just came up, it
      // means the control connection just reconnected, which means we did a full node refresh. So
      // we don't need to process this call.
      LOG.debug("[{}] Ignoring refresh of control node", logPrefix);
      return CompletableFuture.completedFuture(Optional.empty());
    } else if (node.getBroadcastAddress().isPresent()) {
      CompletionStage<AdminResult> query;
      if (isSchemaV2) {
        query =
            query(
                channel,
                "SELECT * FROM "
                    + retrievePeerTableName()
                    + " WHERE peer = :address and peer_port = :port",
                ImmutableMap.of(
                    "address",
                    node.getBroadcastAddress().get().getAddress(),
                    "port",
                    node.getBroadcastAddress().get().getPort()));
      } else {
        query =
            query(
                channel,
                "SELECT * FROM " + retrievePeerTableName() + " WHERE peer = :address",
                ImmutableMap.of("address", node.getBroadcastAddress().get().getAddress()));
      }
      return query.thenApply(result -> firstPeerRowAsNodeInfo(result, localEndPoint));
    } else {
      return query(channel, "SELECT * FROM " + retrievePeerTableName())
          .thenApply(result -> findInPeers(result, node.getHostId(), localEndPoint));
    }
  }

  @Override
  public CompletionStage<Optional<NodeInfo>> getNewNodeInfo(InetSocketAddress broadcastRpcAddress) {
    if (closeFuture.isDone()) {
      return CompletableFutures.failedFuture(new IllegalStateException("closed"));
    }
    LOG.debug("[{}] Fetching info for new node {}", logPrefix, broadcastRpcAddress);
    DriverChannel channel = controlConnection.channel();
    EndPoint localEndPoint = channel.getEndPoint();
    return query(channel, "SELECT * FROM " + retrievePeerTableName())
        .thenApply(result -> findInPeers(result, broadcastRpcAddress, localEndPoint));
  }

  @Override
  public CompletionStage<Iterable<NodeInfo>> refreshNodeList() {
    if (closeFuture.isDone()) {
      return CompletableFutures.failedFuture(new IllegalStateException("closed"));
    }
    LOG.debug("[{}] Refreshing node list", logPrefix);
    DriverChannel channel = controlConnection.channel();
    EndPoint localEndPoint = channel.getEndPoint();

    savePort(channel);

    CompletionStage<AdminResult> localQuery = query(channel, "SELECT * FROM system.local");
    CompletionStage<AdminResult> peersV2Query = query(channel, "SELECT * FROM system.peers_v2");
    CompletableFuture<AdminResult> peersQuery = new CompletableFuture<>();

    peersV2Query.whenComplete(
        (r, t) -> {
          if (t != null) {
            // If system.peers_v2 does not exist, downgrade to system.peers
            if (t instanceof UnexpectedResponseException
                && ((UnexpectedResponseException) t).message instanceof Error) {
              Error error = (Error) ((UnexpectedResponseException) t).message;
              if (error.code == ProtocolConstants.ErrorCode.INVALID
                  // Also downgrade on server error with a specific error message (DSE 6.0.0 to
                  // 6.0.2 with search enabled)
                  || (error.code == ProtocolConstants.ErrorCode.SERVER_ERROR
                      && error.message.contains("Unknown keyspace/cf pair (system.peers_v2)"))) {
                this.isSchemaV2 = false; // We should not attempt this query in the future.
                CompletableFutures.completeFrom(
                    query(channel, "SELECT * FROM system.peers"), peersQuery);
                return;
              }
            }
            peersQuery.completeExceptionally(t);
          } else {
            peersQuery.complete(r);
          }
        });

    return localQuery.thenCombine(
        peersQuery,
        (controlNodeResult, peersResult) -> {
          List<NodeInfo> nodeInfos = new ArrayList<>();
          AdminRow localRow = controlNodeResult.iterator().next();
          InetSocketAddress localBroadcastRpcAddress = getBroadcastRpcAddress(localRow);
          nodeInfos.add(nodeInfoBuilder(localRow, localBroadcastRpcAddress, localEndPoint).build());
          for (AdminRow peerRow : peersResult) {
            if (isPeerValid(peerRow)) {
              InetSocketAddress peerBroadcastRpcAddress = getBroadcastRpcAddress(peerRow);
              NodeInfo nodeInfo =
                  nodeInfoBuilder(peerRow, peerBroadcastRpcAddress, localEndPoint).build();
              nodeInfos.add(nodeInfo);
            }
          }
          return nodeInfos;
        });
  }

  @Override
  public CompletionStage<Boolean> checkSchemaAgreement() {
    if (closeFuture.isDone()) {
      return CompletableFuture.completedFuture(true);
    }
    DriverChannel channel = controlConnection.channel();
    return new SchemaAgreementChecker(channel, context, logPrefix).run();
  }

  @NonNull
  @Override
  public CompletionStage<Void> closeFuture() {
    return closeFuture;
  }

  @NonNull
  @Override
  public CompletionStage<Void> closeAsync() {
    closeFuture.complete(null);
    return closeFuture;
  }

  @NonNull
  @Override
  public CompletionStage<Void> forceCloseAsync() {
    return closeAsync();
  }

  @VisibleForTesting
  protected CompletionStage<AdminResult> query(
      DriverChannel channel, String queryString, Map<String, Object> parameters) {
    return AdminRequestHandler.query(
            channel, queryString, parameters, timeout, INFINITE_PAGE_SIZE, logPrefix)
        .start();
  }

  private CompletionStage<AdminResult> query(DriverChannel channel, String queryString) {
    return query(channel, queryString, Collections.emptyMap());
  }

  private String retrievePeerTableName() {
    if (isSchemaV2) {
      return "system.peers_v2";
    }
    return "system.peers";
  }

  private Optional<NodeInfo> firstPeerRowAsNodeInfo(AdminResult result, EndPoint localEndPoint) {
    Iterator<AdminRow> iterator = result.iterator();
    if (iterator.hasNext()) {
      AdminRow row = iterator.next();
      if (isPeerValid(row)) {
        InetSocketAddress peerBroadcastRpcAddress = getBroadcastRpcAddress(row);
        return Optional.of(nodeInfoBuilder(row, peerBroadcastRpcAddress, localEndPoint).build());
      }
    }
    return Optional.empty();
  }

  /**
   * Creates a {@link DefaultNodeInfo.Builder} instance from the given row.
   *
   * @param broadcastRpcAddress this is a parameter only because we already have it when we come
   *     from {@link #findInPeers(AdminResult, InetSocketAddress, EndPoint)}. Callers that don't
   *     already have it can use {@link #getBroadcastRpcAddress}. For the control host, this can be
   *     null; if this node is a peer however, this cannot be null, since we use that address to
   *     create the node's endpoint. Callers can use {@link #isPeerValid(AdminRow)} to check that
   *     before calling this method.
   * @param localEndPoint the control node endpoint that was used to query the node's system tables.
   *     This is a parameter because it would be racy to call {@code
   *     controlConnection.channel().getEndPoint()} from within this method, as the control
   *     connection may have changed its channel since. So this parameter must be provided by the
   *     caller.
   */
  @NonNull
  protected DefaultNodeInfo.Builder nodeInfoBuilder(
      @NonNull AdminRow row,
      @Nullable InetSocketAddress broadcastRpcAddress,
      @NonNull EndPoint localEndPoint) {

    boolean peer = row.contains("peer");

    EndPoint endPoint;
    if (peer) {
      // If this node is a peer, its broadcast RPC address must be present.
      Objects.requireNonNull(
          broadcastRpcAddress, "broadcastRpcAddress cannot be null for a peer row");
      // Deployments that use a custom EndPoint implementation will need their own TopologyMonitor.
      // One simple approach is to extend this class and override this method.
      endPoint = new DefaultEndPoint(context.getAddressTranslator().translate(broadcastRpcAddress));
    } else {
      // Don't rely on system.local.rpc_address for the control node, because it mistakenly
      // reports the normal RPC address instead of the broadcast one (CASSANDRA-11181). We
      // already know the endpoint anyway since we've just used it to query.
      endPoint = localEndPoint;
    }

    // in system.local
    InetAddress broadcastInetAddress = row.getInetAddress("broadcast_address");
    if (broadcastInetAddress == null) {
      // in system.peers or system.peers_v2
      broadcastInetAddress = row.getInetAddress("peer");
    }

    Integer broadcastPort = 0;
    if (row.contains("broadcast_port")) {
      // system.local for Cassandra >= 4.0
      broadcastPort = row.getInteger("broadcast_port");
    } else if (row.contains("peer_port")) {
      // system.peers_v2
      broadcastPort = row.getInteger("peer_port");
    }

    InetSocketAddress broadcastAddress = null;
    if (broadcastInetAddress != null && broadcastPort != null) {
      broadcastAddress = new InetSocketAddress(broadcastInetAddress, broadcastPort);
    }

    // in system.local only, and only for Cassandra versions >= 2.0.17, 2.1.8, 2.2.0 rc2;
    // not present in system.peers nor system.peers_v2
    InetAddress listenInetAddress = row.getInetAddress("listen_address");

    // in system.local only, and only for Cassandra >= 4.0
    Integer listenPort = 0;
    if (row.contains("listen_port")) {
      listenPort = row.getInteger("listen_port");
    }

    InetSocketAddress listenAddress = null;
    if (listenInetAddress != null && listenPort != null) {
      listenAddress = new InetSocketAddress(listenInetAddress, listenPort);
    }

    return DefaultNodeInfo.builder()
        .withEndPoint(endPoint)
        .withBroadcastRpcAddress(broadcastRpcAddress)
        .withBroadcastAddress(broadcastAddress)
        .withListenAddress(listenAddress)
        .withDatacenter(row.getString("data_center"))
        .withRack(row.getString("rack"))
        .withCassandraVersion(row.getString("release_version"))
        .withTokens(row.getSetOfString("tokens"))
        .withPartitioner(row.getString("partitioner"))
        .withHostId(Objects.requireNonNull(row.getUuid("host_id")))
        .withSchemaVersion(row.getUuid("schema_version"));
  }

  // Called when a new node is being added; the peers table is keyed by broadcast_address,
  // but the received event only contains broadcast_rpc_address, so
  // we have to traverse the whole table and check the rows one by one.
  private Optional<NodeInfo> findInPeers(
      AdminResult result, InetSocketAddress broadcastRpcAddressToFind, EndPoint localEndPoint) {
    for (AdminRow row : result) {
      InetSocketAddress broadcastRpcAddress = getBroadcastRpcAddress(row);
      if (broadcastRpcAddress != null
          && broadcastRpcAddress.equals(broadcastRpcAddressToFind)
          && isPeerValid(row)) {
        return Optional.of(nodeInfoBuilder(row, broadcastRpcAddress, localEndPoint).build());
      }
    }
    LOG.debug("[{}] Could not find any peer row matching {}", logPrefix, broadcastRpcAddressToFind);
    return Optional.empty();
  }

  // Called when refreshing an existing node, and we don't know its broadcast address; in this
  // case we attempt a search by host id and have to traverse the whole table and check the rows one
  // by one.
  private Optional<NodeInfo> findInPeers(
      AdminResult result, UUID hostIdToFind, EndPoint localEndPoint) {
    for (AdminRow row : result) {
      UUID hostId = row.getUuid("host_id");
      if (hostId != null && hostId.equals(hostIdToFind) && isPeerValid(row)) {
        InetSocketAddress broadcastRpcAddress = getBroadcastRpcAddress(row);
        return Optional.of(nodeInfoBuilder(row, broadcastRpcAddress, localEndPoint).build());
      }
    }
    LOG.debug("[{}] Could not find any peer row matching {}", logPrefix, hostIdToFind);
    return Optional.empty();
  }

  // Current versions of Cassandra (3.11 at the time of writing), require the same port for all
  // nodes. As a consequence, the port is not stored in system tables.
  // We save it the first time we get a control connection channel.
  private void savePort(DriverChannel channel) {
    if (port < 0) {
      SocketAddress address = channel.getEndPoint().resolve();
      if (address instanceof InetSocketAddress) {
        port = ((InetSocketAddress) address).getPort();
      }
    }
  }

  /**
   * Determines the broadcast RPC address of the node represented by the given row.
   *
   * @param row The row to inspect; can represent either a local (control) node or a peer node.
   * @return the broadcast RPC address of the node, if it could be determined; or {@code null}
   *     otherwise.
   */
  @Nullable
  protected InetSocketAddress getBroadcastRpcAddress(@NonNull AdminRow row) {
    // in system.peers or system.local
    InetAddress broadcastRpcInetAddress = row.getInetAddress("rpc_address");
    if (broadcastRpcInetAddress == null) {
      // in system.peers_v2 (Cassandra >= 4.0)
      broadcastRpcInetAddress = row.getInetAddress("native_address");
      if (broadcastRpcInetAddress == null) {
        // This could only happen if system tables are corrupted, but handle gracefully
        return null;
      }
    }
    // system.local for Cassandra >= 4.0
    Integer broadcastRpcPort = row.getInteger("rpc_port");
    if (broadcastRpcPort == null || broadcastRpcPort == 0) {
      // system.peers_v2
      broadcastRpcPort = row.getInteger("native_port");
      if (broadcastRpcPort == null || broadcastRpcPort == 0) {
        // use the default port if no port information was found in the row;
        // note that in rare situations, the default port might not be known, in which case we
        // report zero, as advertised in the javadocs of Node and NodeInfo.
        broadcastRpcPort = port == -1 ? 0 : port;
      }
    }
    return new InetSocketAddress(broadcastRpcInetAddress, broadcastRpcPort);
  }

  /**
   * Returns {@code true} if the given peer row is valid, and {@code false} otherwise.
   *
   * <p>This method must at least ensure that the row contains enough information to extract the
   * node's broadcast RPC address and host ID; otherwise the driver may not work properly.
   */
  protected boolean isPeerValid(AdminRow peerRow) {
    boolean hasPeersRpcAddress = peerRow.getInetAddress("rpc_address") != null;
    boolean hasPeersV2RpcAddress =
        peerRow.getInetAddress("native_address") != null
            && peerRow.getInteger("native_port") != null;
    boolean hasRpcAddress = hasPeersV2RpcAddress || hasPeersRpcAddress;
    boolean hasHostId = peerRow.getUuid("host_id") != null;
    boolean valid = hasRpcAddress && hasHostId;
    if (!valid) {
      LOG.warn(
          "[{}] Found invalid row in {} for peer: {}. "
              + "This is likely a gossip or snitch issue, this node will be ignored.",
          logPrefix,
          retrievePeerTableName(),
          peerRow.getInetAddress("peer"));
    }
    return valid;
  }
}
