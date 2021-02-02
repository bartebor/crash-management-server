package pl.wp.crashmanagementserver;

import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.BindConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SocketOption;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SnapshotGenerator {
	@Autowired
	EnvoyDiscoveryServer server;

	private final AtomicLong cdsVersion = new AtomicLong(1L);

	@Value("${clusters.count:2}")
	private int numberOfClusters;

	@Value("${clusters.endoint.ip:127.0.0.1}")
	private String endpointIp;

	@Value("${clusters.endoint.ip:3333}")
	private int endpointPort;

	private long connectTimeout = 1L;

	private List<Cluster> cds;
	private List<ClusterLoadAssignment> eds;
	private final List<Listener> lds = Collections.EMPTY_LIST;
	private final List<RouteConfiguration> rds = Collections.EMPTY_LIST;
	private final List<Secret> sds = Collections.EMPTY_LIST;

	@PostConstruct
	private void init() {
		eds = generateEds();
		cds = generateCds();

		publish();
	}

	public void changeCds() {
		++connectTimeout;
		cds = generateCds();
		publish();
	}

	private void publish() {
		long version = cdsVersion.incrementAndGet();

		try {
			server.publishSnapshot(cds, Long.toString(version),
				eds, Long.toString(version),
				lds, "1",
				rds, "1",
				sds, "1");
		} catch (IOException ex) {
			log.error("Error in publishing snapshot", ex);
		}
	}

	private static String makeClusterName(int id) {
		return "cluster-" + id;
	}

	private List<ClusterLoadAssignment> generateEds() {
		List<ClusterLoadAssignment> clas = new ArrayList<>(numberOfClusters);
		for (int id = 1; id <= numberOfClusters; ++id) {
			clas.add(createClusterLoadAssigment(id));
		}
		return clas;
	}

	private ClusterLoadAssignment createClusterLoadAssigment(int id) {
		final String clusterName = makeClusterName(id);

		final ClusterLoadAssignment.Builder edsBuilder = ClusterLoadAssignment.newBuilder()
			.setClusterName(clusterName);

		SocketAddress.Protocol protocol = (id % 10 == 1) ? SocketAddress.Protocol.UDP: SocketAddress.Protocol.TCP;
		log.info("cluster {}, protocol {}", clusterName, protocol);

		final Endpoint.Builder endpointBuilder = Endpoint.newBuilder()
			.setAddress(Address.newBuilder()
				.setSocketAddress(
					SocketAddress.newBuilder()
						.setAddress(endpointIp)
						.setPortValue(endpointPort)
						.setProtocol(protocol)
				)
			)
			.setHostname(clusterName);

		final LbEndpoint.Builder lbEndpointBuilder = LbEndpoint.newBuilder()
			.setEndpoint(endpointBuilder)
			.setHealthStatus(HealthStatus.HEALTHY);


		edsBuilder.addEndpoints(LocalityLbEndpoints.newBuilder()
			.addLbEndpoints(lbEndpointBuilder)
		);

		return edsBuilder.build();
	}

	private List<Cluster> generateCds() {
		List<Cluster> clusters = new ArrayList<>(numberOfClusters);
		for (int id = 1; id <= numberOfClusters; ++id) {
			clusters.add(createCluster(id));
		}
		return clusters;
	}

	private Cluster createCluster(int id) {
		final String clusterName = makeClusterName(id);

		final Cluster.Builder clusterBuilder = Cluster.newBuilder();
		clusterBuilder.setName(clusterName)
		.setAltStatName(clusterName)
			.setType(Cluster.DiscoveryType.EDS)
			.setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
				.setEdsConfig(ConfigSource.newBuilder()
					.setResourceApiVersion(ApiVersion.V3)
					.setAds(AggregatedConfigSource.getDefaultInstance())
				)
			)
			.setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
			.setCircuitBreakers(CircuitBreakers.newBuilder()
				.addThresholds(Thresholds.newBuilder()
					.setMaxConnections(UInt32Value.of(20_000))
					.setMaxPendingRequests(UInt32Value.of(2000))
					.setMaxRequests(UInt32Value.of(20000))
					.setMaxRetries(UInt32Value.of(16))
				)
			)
			.setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY)
			.setOutlierDetection(OutlierDetection.newBuilder()
				.setInterval(Duration.newBuilder().setSeconds(1L))
				.setBaseEjectionTime(Duration.newBuilder().setSeconds(5L))
				.setConsecutiveGatewayFailure(UInt32Value.of(5))
			)
			.setUpstreamBindConfig(BindConfig.newBuilder()
				.setSourceAddress(SocketAddress.newBuilder()
					.setAddress("0.0.0.0")
					.setPortValue(0)
				)
				.addSocketOptions(SocketOption.newBuilder()
					.setName(24)
					.setIntValue(1)
				)
			)
			.setIgnoreHealthOnHostRemoval(true);

		return clusterBuilder.build();
	}
}
