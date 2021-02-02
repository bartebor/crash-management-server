package pl.wp.crashmanagementserver;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EnvoyDiscoveryServer {

	private static final String DEFAULT_GROUP = "default";

	private static final NodeGroup<String> NODE_GROUP = new NodeGroup<String>() {
		@Override
		public String hash(Node node) {
			return DEFAULT_GROUP;
		}

		@Override
		public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
			return DEFAULT_GROUP;
		}
	};

	/**
	 * Snapshot cache containing snapshots for different envoy groups.
	 * For now, we support only one such group and map all envoys to it.
	 */
	private final SimpleCache<String> snapshotCache = new SimpleCache<>(NODE_GROUP);

	private final AtomicBoolean started = new AtomicBoolean();

	private Server server;

	@Value("${envoy.listener.ip:}")
	private String envoyListenerIp;

	@Value("${envoy.listener.port:12345}")
	private Integer envoyListenerPort;

	public void publishSnapshot(
		Iterable<Cluster> clusters,
		String clustersVersion,
		Iterable<ClusterLoadAssignment> endpoints,
		String endpointsVersion,
		Iterable<Listener> listeners,
		String listenersVersion,
		Iterable<RouteConfiguration> routes,
		String routesVersion,
		Iterable<Secret> secrets,
		String secretsVersion) throws IOException {

		snapshotCache.setSnapshot(DEFAULT_GROUP, Snapshot.create(
			clusters, clustersVersion,
			endpoints, endpointsVersion,
			listeners, listenersVersion,
			routes, routesVersion,
			secrets, routesVersion));

		if (started.compareAndSet(false, true)) {
			// start server when first snapshot is ready, so envoy will not get empty response
			log.info("First snapshot generated, starting envoy discovery server...");
			server.start();
			log.info("Discovery server has started.");
		}
	}

	public void restart() {
		destroy();
		init();
	}

	@PostConstruct
	private void init() {
		final V3DiscoveryServer discoveryServerV3 = new V3DiscoveryServer(snapshotCache);

		SocketAddress socketAddress = envoyListenerIp.isEmpty()
			? new InetSocketAddress(envoyListenerPort)
			: new InetSocketAddress(envoyListenerIp, envoyListenerPort);

		log.info("CONFIG: Envoy listener configured on {}", socketAddress.toString());

		ServerBuilder builder = NettyServerBuilder.forAddress(socketAddress)
			.addService(discoveryServerV3.getAggregatedDiscoveryServiceImpl());

		server = builder.build();
	}

	@PreDestroy
	private void destroy() {
		if (!started.get()) {
			return;
		}

		boolean terminated = false;

		try {
			server.shutdown();
			if (server.awaitTermination(5, TimeUnit.SECONDS)) {
				terminated = true;
			}
		} catch (InterruptedException ex) {
		}

		if (!terminated) {
			server.shutdownNow();
		}

		server = null;
		started.set(false);
	}
}
